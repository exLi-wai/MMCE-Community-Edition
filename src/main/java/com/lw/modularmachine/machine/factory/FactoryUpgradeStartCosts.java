package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.api.upgrade.FactoryUpgradeItemData;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import com.lw.modularmachine.machine.recipe.ItemStackSnapshots;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * Shared no-write startup preparation, including costs and owned item-data
 * transforms.
 */
public final class FactoryUpgradeStartCosts {
  public enum Status {
    READY,
    PENDING,
    ERROR,
    STALE
  }

  public record Result(Status status, int workUsed) {
    public Result {
      Objects.requireNonNull(status, "status");
      if (workUsed < 0) {
        throw new IllegalArgumentException("Negative startup preparation work");
      }
    }
  }

  private record Source(UpgradeBusBlockEntity bus, long revision, FactoryUpgradeCatalog catalog) {
  }

  private static final class Replacement {
    private final Source source;
    private final NonNullList<ItemStack> contents;
    private UpgradeBusDataHandlers.State busData;
    private boolean modified;

    private Replacement(Source source, NonNullList<ItemStack> contents, boolean modified, UpgradeBusDataHandlers.State busData) {
      this.source = source;
      this.contents = contents;
      this.modified = modified;
      this.busData = busData;
    }
  }

  private interface DataStep {
    void prepare(FactoryUpgradeItemData.Context context);
  }

  private record ItemStep(Replacement replacement, UpgradeItemDataHandlers.Subscription subscription)
      implements
        DataStep {
    @Override
    public void prepare(FactoryUpgradeItemData.Context context) {
      var stack = replacement.contents.get(subscription.upgrade().slot());
      var update = UpgradeItemDataHandlers.prepare(subscription, context, stack);
      replacement.modified |= UpgradeItemDataHandlers.apply(stack, update);
    }
  }

  private record BusStep(Replacement replacement, UpgradeBusDataHandlers.Subscription subscription)
      implements
        DataStep {
    @Override
    public void prepare(FactoryUpgradeItemData.Context context) {
      var update = UpgradeBusDataHandlers.prepare(subscription, context, replacement.busData);
      var next = replacement.busData.with(subscription.definitionId(), update);
      replacement.modified |= next != replacement.busData;
      replacement.busData = next;
    }
  }

  private FactoryUpgradeStartCosts() {
  }

  public static int maxRuns(UpgradeBusOwner factory) {
    FactoryUpgradeHandlers.requireServer();
    int limit = Integer.MAX_VALUE;
    for (var bus : factory.upgradeCallbackBuses()) {
      if (bus.runtimeDataRequired(factory) && !bus.upgradeCatalogValid(factory)) {
        return 0;
      }
      if (!bus.startCostRequired(factory) && !bus.startDataRequired(factory) && !bus.startBusDataRequired(factory)) {
        continue;
      }
      if (!bus.upgradeCatalogValid(factory)
          || bus.upgradeRevision() == Long.MAX_VALUE
          || bus.startCostRequired(factory) && bus.startCostSlots(factory).isEmpty()) {
        return 0;
      }
      for (var slot : bus.startCostSlots(factory)) {
        limit = Math.min(limit, slot.cost().maxRuns(bus.inventory().getStackInSlot(slot.stackSlot())));
      }
    }

    return limit;
  }

  public static Preparation prepare(UpgradeBusOwner factory, int runs) {
    FactoryUpgradeHandlers.requireServer();

    return new Preparation(factory, null, null, runs);
  }

  public static Preparation prepare(UpgradeBusOwner factory, String threadId, ResourceLocation recipeId, int runs) {
    FactoryUpgradeHandlers.requireServer();
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(recipeId, "recipeId");

    return new Preparation(factory, threadId, recipeId, runs);
  }

  public static void publish(UpgradeBusOwner factory, List<UpgradeBusBlockEntity.StartCostLease> leases) {
    if (leases.isEmpty()) {
      return;
    }
    boolean entered = FactoryUpgradeCallbacks.enter(factory);
    try {
      for (var lease : leases) {
        try {
          lease.publish();
        } catch (RuntimeException failure) {
          ModularMachine.LOGGER
              .warn("Committed upgrade startup notification failed at {}", factory.getBlockPos(), failure);
        }
      }
    } finally {
      if (entered) {
        FactoryUpgradeCallbacks.exit(factory);
      }
    }
  }

  public static final class Preparation {
    private final UpgradeBusOwner factory;
    private final Object registration;
    private final long epoch;
    private final List<Source> sources;
    private final List<Replacement> replacements;
    private final List<DataStep> dataSteps;
    private final boolean payable;
    private final FactoryUpgradeItemData.Context context;
    private final UpgradeStartState threadConfig;
    private int cursor;
    private Status status;

    private Preparation(UpgradeBusOwner factory, String threadId, ResourceLocation recipeId, int runs) {
      if (runs < 1) {
        throw new IllegalArgumentException("Startup preparation needs a positive run count");
      }
      this.factory = Objects.requireNonNull(factory, "factory");
      registration = factory.structureRegistration();
      epoch = FactoryUpgradeService.snapshot().epoch();
      threadConfig = threadId == null ? null : Objects
          .requireNonNull(factory.upgradeStartState(threadId), "upgrade start state");
      var level = (ServerLevel) factory.getLevel();
      context = threadConfig == null ? null : new FactoryUpgradeItemData.Context(
          factory.machineId(),
          recipeId,
          level.dimension().location(),
          factory.getBlockPos(),
          threadId,
          runs,
          epoch,
          threadConfig.upgradeSnapshot());
      var captured = new ArrayList<Source>();
      var changes = new ArrayList<Replacement>();
      var steps = new ArrayList<DataStep>();
      var busSteps = new ArrayList<DataStep>();
      boolean affordable = true;
      for (var bus : factory.upgradeCallbackBuses()) {
        var source = new Source(bus, bus.upgradeRevision(), bus.upgradeCatalog(factory));
        captured.add(source);
        if (!bus.startCostRequired(factory) && !bus.startDataRequired(factory) && !bus.startBusDataRequired(factory)) {
          continue;
        }
        if (context == null && (bus.startDataRequired(factory) || bus.startBusDataRequired(factory))) {
          throw new IllegalArgumentException("Upgrade data requires a recipe/thread startup context");
        }
        if (!bus.upgradeCatalogValid(factory)
            || source.revision() == Long.MAX_VALUE
            || bus.startCostRequired(factory) && bus.startCostSlots(factory).isEmpty()
            || bus.startDataRequired(factory) && bus.startDataSubscriptions(factory).isEmpty()
            || bus.startBusDataRequired(factory) && bus.startBusDataSubscriptions(factory).isEmpty()) {
          affordable = false;
          continue;
        }
        var contents = NonNullList.withSize(bus.inventory().getSlots(), ItemStack.EMPTY);
        for (int slot = 0; slot < contents.size(); slot++) {
          contents.set(slot, ItemStackSnapshots.detached(bus.inventory().getStackInSlot(slot), level.registryAccess()));
        }
        var replacement = new Replacement(
            source,
            contents,
            !bus.startCostSlots(factory).isEmpty(),
            bus.busDataSnapshot());
        boolean busAffordable = true;
        for (var slot : bus.startCostSlots(factory)) {
          var stack = contents.get(slot.stackSlot());
          if (slot.cost().maxRuns(stack) < runs) {
            affordable = false;
            busAffordable = false;
          } else {
            contents.set(slot.stackSlot(), slot.cost().apply(stack, runs));
          }
        }
        if (busAffordable) {
          changes.add(replacement);
          for (var subscription : bus.startDataSubscriptions(factory)) {
            if (!contents.get(subscription.upgrade().slot()).isEmpty()) {
              steps.add(new ItemStep(replacement, subscription));
            }
          }
          for (var subscription : bus.startBusDataSubscriptions(factory)) {
            busSteps.add(new BusStep(replacement, subscription));
          }
        }
      }
      sources = List.copyOf(captured);
      replacements = List.copyOf(changes);
      steps.addAll(busSteps);
      dataSteps = List.copyOf(steps);
      payable = affordable;
      status = dataSteps.isEmpty() ? Status.READY : Status.PENDING;
    }

    public boolean payable() {
      return payable;
    }

    public boolean current() {
      FactoryUpgradeHandlers.requireServer();
      if (!(factory.getLevel() instanceof ServerLevel level)
          || factory.isRemoved()
          || !level.hasChunkAt(factory.getBlockPos())
          || level.getBlockEntity(factory.getBlockPos()) != factory
          || factory.structureRegistration() != registration
          || !factory.canCommitStructure()
          || FactoryUpgradeService.snapshot().epoch() != epoch) {
        return false;
      }
      if (threadConfig != null) {
        var thread = factory.upgradeStartState(threadConfig.threadId());
        if (thread == null
            || thread.enabled() != threadConfig.enabled()
            || !thread.machineId().equals(threadConfig.machineId())
            || !Objects.equals(thread.lockedRecipe(), threadConfig.lockedRecipe())
            || !thread.upgradeSnapshot().equals(threadConfig.upgradeSnapshot())) {
          return false;
        }
      }
      var buses = factory.upgradeCallbackBuses();
      if (buses.size() != sources.size()) {
        return false;
      }
      for (int index = 0; index < sources.size(); index++) {
        var source = sources.get(index);
        if (buses.get(index) != source.bus()
            || source.bus().upgradeRevision() != source.revision()
            || source.bus().upgradeCatalog(factory) != source.catalog()) {
          return false;
        }
      }

      return true;
    }

    public Result advance(int budget) {
      FactoryUpgradeHandlers.requireServer();
      if (budget < 0) {
        throw new IllegalArgumentException("Negative startup preparation budget");
      }
      if (!current()) {
        return new Result(Status.STALE, 0);
      }
      if (!payable) {
        return new Result(Status.ERROR, 0);
      }
      if (status != Status.PENDING || budget == 0) {
        return new Result(status, 0);
      }
      if (!FactoryUpgradeCallbacks.enter(factory)) {
        return new Result(Status.STALE, 0);
      }
      var locked = new ArrayList<UpgradeBusBlockEntity>();
      int used = 0;
      try {
        for (var source : sources) {
          if (source.bus().upgradeCatalogValid(factory)) {
            if (!source.bus().beginLifecycleNotification(factory)) {
              return new Result(Status.STALE, 0);
            }
            locked.add(source.bus());
          }
        }
        while (cursor < dataSteps.size() && used < budget) {
          var step = dataSteps.get(cursor);
          used++;
          step.prepare(context);
          cursor++;
        }
        if (cursor == dataSteps.size()) {
          status = Status.READY;
        }
      } catch (RuntimeException failure) {
        status = Status.ERROR;
        if (FactoryUpgradeService.snapshot().epoch() == epoch) {
          ModularMachine.LOGGER.warn(
              "Upgrade startup data preparation failed at {} for {}",
              factory.getBlockPos(),
              context.recipeId(),
              failure);
        }
      } finally {
        for (int index = locked.size() - 1; index >= 0; index--) {
          locked.get(index).endLifecycleNotification();
        }
        FactoryUpgradeCallbacks.exit(factory);
      }

      return new Result(current() ? status : Status.STALE, used);
    }

    public void acquireInto(List<UpgradeBusBlockEntity.StartCostLease> leases) {
      if (!payable || status != Status.READY || !current()) {
        throw new IllegalStateException("Stale or incomplete upgrade startup preparation");
      }
      for (var replacement : replacements) {
        if (replacement.modified) {
          var source = replacement.source;
          leases.add(
              source.bus().acquireStartup(
                  factory,
                  source.revision(),
                  source.catalog(),
                  replacement.contents,
                  replacement.busData));
        }
      }
    }
  }
}
