package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.api.upgrade.FactoryUpgradeStartCost;

import com.lw.modularmachine.api.upgrade.FactoryUpgradeRuntimeData;

import com.lw.modularmachine.api.upgrade.FactoryUpgradeItemData;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.block.UpgradeBusBlock;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.recipe.FrozenItemRun;
import com.lw.modularmachine.machine.recipe.ItemStackSnapshots;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Resumable runtime upgrade preparation and its physical replacement leases.
 */
public final class FactoryUpgradeTickCosts {
  public enum Status {
    READY,
    INSUFFICIENT,
    INVALID,
    PENDING,
    ERROR
  }

  private static final class Source {
    private final UpgradeBusBlockEntity bus;
    private final BlockPos position;
    private final BlockState state;
    private final long revision;
    private final List<UpgradeBusBlockEntity.StartCostSlot> costs;
    private final List<UpgradeRuntimeDataHandlers.Subscription> subscriptions;
    private final NonNullList<ItemStack> before;
    private final NonNullList<ItemStack> prepared;
    private final UpgradeBusDataHandlers.State beforeData;
    private UpgradeBusDataHandlers.State preparedData;
    private boolean changed;

    private Source(UpgradeBusBlockEntity bus, BlockPos position, BlockState state, long revision, List<UpgradeBusBlockEntity.StartCostSlot> costs, List<UpgradeRuntimeDataHandlers.Subscription> subscriptions, NonNullList<ItemStack> before, NonNullList<ItemStack> prepared, UpgradeBusDataHandlers.State beforeData) {
      this.bus = bus;
      this.position = position;
      this.state = state;
      this.revision = revision;
      this.costs = costs;
      this.subscriptions = subscriptions;
      this.before = before;
      this.prepared = prepared;
      this.beforeData = beforeData;
      preparedData = beforeData;
      changed = !costs.isEmpty();
    }
  }

  private record Step(Source source, UpgradeRuntimeDataHandlers.Subscription subscription) {
  }

  private final UpgradeBusOwner owner;
  private final FactoryUpgradeRuntimeData.Phase phase;
  private final String threadId;
  private final List<FrozenItemRun> runs;
  private final long instances;
  private final ServerLevel level;
  private final StructureService.Registration registration;
  private final ResourceLocation machine;
  private final long epoch;
  private final long preparedAtTick;
  private long authorityTick;
  private final List<Source> sources;
  private final List<Step> steps;
  private List<FactoryUpgradeRuntimeData.Run> runContext;
  private int cursor;
  private Status status = Status.PENDING;
  private final boolean legacy;

  private FactoryUpgradeTickCosts(UpgradeBusOwner owner, FactoryUpgradeRuntimeData.Phase phase, String threadId, List<FrozenItemRun> runs, long fixedInstances) {
    this.owner = owner;
    this.phase = Objects.requireNonNull(phase, "phase");
    this.threadId = threadId;
    this.runs = List.copyOf(runs);
    if (!(owner.getLevel() instanceof ServerLevel server)) {
      throw new IllegalStateException("Runtime upgrade work requires a server level");
    }
    level = server;
    ServerThreadGuard.require(level, "Runtime upgrade work");
    registration = owner.structureRegistration();
    machine = owner.machineId();
    epoch = FactoryUpgradeService.snapshot().epoch();
    preparedAtTick = level.getGameTime();
    authorityTick = preparedAtTick;
    legacy = threadId == null && phase == FactoryUpgradeRuntimeData.Phase.TICK;
    long computedInstances;
    try {
      computedInstances = fixedInstances > 0 ? fixedInstances : calculateInstances();
    } catch (ArithmeticException overflow) {
      computedInstances = 1;
      status = Status.INVALID;
    }
    instances = computedInstances;
    if (status == Status.INVALID
        || instances < 1
        || !legacy
            && (runs.isEmpty()
                || runs.stream()
                    .anyMatch(run -> run.pendingOutput() != (phase == FactoryUpgradeRuntimeData.Phase.FINISHED)))
        || legacy && FactoryUpgradeService.snapshot().hasTickData()
        || UpgradeLifecyclePublisher.isDispatching(owner)
        || !owner.canCommitStructure()
        || !runsCurrent()) {
      sources = List.of();
      steps = List.of();
      status = Status.INVALID;
      return;
    }
    List<Source> captured;
    try {
      var definitions = FactoryUpgradeService.snapshot();
      captured = definitions.hasWork(phase) ? capture() : List.of();
    } catch (RuntimeException invalid) {
      captured = List.of();
      status = Status.INVALID;
    }
    sources = captured;
    var pendingSteps = new ArrayList<Step>();
    for (var source : sources) {
      for (var subscription : source.subscriptions) {
        pendingSteps.add(new Step(source, subscription));
      }
    }
    steps = List.copyOf(pendingSteps);
    if (status == Status.INVALID || status == Status.INSUFFICIENT) {
      return;
    }
    if (steps.stream().anyMatch(step -> !FactoryUpgradeRuntimeData.isRegistered(step.subscription().handlerId()))) {
      status = Status.ERROR;
    } else if (steps.isEmpty()) {
      status = Status.READY;
    } else {
      status = Status.PENDING;
    }
  }

  public static FactoryUpgradeTickCosts prepare(UpgradeBusOwner owner, long instances) {
    return new FactoryUpgradeTickCosts(owner, FactoryUpgradeRuntimeData.Phase.TICK, null, List.of(), instances);
  }

  public static FactoryUpgradeTickCosts prepare(UpgradeBusOwner owner, String threadId, List<FrozenItemRun> runs) {
    return prepare(owner, FactoryUpgradeRuntimeData.Phase.TICK, threadId, runs);
  }

  public static FactoryUpgradeTickCosts prepare(UpgradeBusOwner owner, FactoryUpgradeRuntimeData.Phase phase, String threadId, List<FrozenItemRun> runs) {
    return new FactoryUpgradeTickCosts(owner, phase, threadId, runs, 0);
  }

  private long calculateInstances() {
    long total = 0;
    for (var run : runs) {
      total = Math.addExact(total, run.parallelInstances());
    }

    return total;
  }

  private List<Source> capture() {
    var result = new ArrayList<Source>();
    for (var candidate : owner.upgradeBusPositions()) {
      if (!level.hasChunkAt(candidate)) {
        throw new IllegalStateException("Unavailable upgrade bus position");
      }
      var position = candidate.immutable();
      var state = level.getBlockState(position);
      if (!(state.getBlock() instanceof UpgradeBusBlock)) {
        result.add(
            new Source(
                null,
                position,
                state,
                0,
                List.of(),
                List.of(),
                null,
                null,
                UpgradeBusDataHandlers.State.empty()));
        continue;
      }
      if (!(level.getBlockEntity(position) instanceof UpgradeBusBlockEntity bus) || !bus.hasFactoryBinding(owner)) {
        throw new IllegalStateException("Unavailable runtime upgrade bus");
      }
      bus.refreshUpgradeDefinitions();
      if (!bus.tickCostsReady(owner) || bus.upgradeRevision() == Long.MAX_VALUE) {
        throw new IllegalStateException("Unavailable runtime upgrade metadata");
      }
      if (MachineConfig.CONSERVATIVE_COMMIT_CHECK.get() && !bus.auditTickCosts(owner)) {
        throw new IllegalStateException("Runtime upgrade source audit failed");
      }
      var costs = phase == FactoryUpgradeRuntimeData.Phase.TICK ? bus
          .tickCostSlots(owner) : List.<UpgradeBusBlockEntity.StartCostSlot>of();
      var subscriptions = legacy ? List.<UpgradeRuntimeDataHandlers.Subscription>of() : bus
          .runtimeDataSubscriptions(owner, phase);
      if (costs.isEmpty() && subscriptions.isEmpty()) {
        result.add(
            new Source(
                bus,
                position,
                state,
                bus.upgradeRevision(),
                costs,
                subscriptions,
                null,
                null,
                bus.busDataSnapshot()));
        continue;
      }
      var before = NonNullList.withSize(bus.slotCount(), ItemStack.EMPTY);
      var prepared = NonNullList.withSize(bus.tier().slots(), ItemStack.EMPTY);
      for (int slot = 0; slot < before.size(); slot++) {
        before.set(slot, ItemStackSnapshots.detached(bus.inventory().getStackInSlot(slot), level.registryAccess()));
        prepared.set(slot, before.get(slot).copy());
      }
      var beforeData = bus.busDataSnapshot();
      for (var cost : costs) {
        var stack = prepared.get(cost.stackSlot());
        if (canPay(cost.cost(), stack)) {
          prepared.set(cost.stackSlot(), cost.cost().apply(stack, Math.toIntExact(instances)));
        } else {
          status = Status.INSUFFICIENT;
        }
      }
      result.add(
          new Source(bus, position, state, bus.upgradeRevision(), costs, subscriptions, before, prepared, beforeData));
    }

    return List.copyOf(result);
  }

  private boolean canPay(FactoryUpgradeStartCost cost, ItemStack stack) {
    try {
      return cost.maxRuns(stack) >= instances;
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  public Status status() {
    return status;
  }

  public boolean hasCharges() {
    return sources.stream().anyMatch(source -> !source.costs.isEmpty());
  }

  public boolean hasRuntimeWork() {
    return hasCharges() || !steps.isEmpty();
  }

  public boolean reusable(String expectedThread, List<FrozenItemRun> expectedRuns) {
    return reusable(FactoryUpgradeRuntimeData.Phase.TICK, expectedThread, expectedRuns);
  }

  public boolean reusable(FactoryUpgradeRuntimeData.Phase expectedPhase, String expectedThread, List<FrozenItemRun> expectedRuns) {
    if (phase != expectedPhase
        || !Objects.equals(threadId, expectedThread)
        || !runs.equals(expectedRuns)
        || status == Status.INVALID) {
      return false;
    }

    return stampsCurrent(false) && contentsCurrent() && stampsCurrent(false);
  }

  public void authorizeTick() {
    ServerThreadGuard.require(level, "Runtime upgrade tick authorization");
    if (stampsCurrent(false)) {
      authorityTick = level.getGameTime();
    } else {
      status = Status.INVALID;
    }
  }

  public int advanceData(int allowance) {
    ServerThreadGuard.require(level, "Runtime upgrade proposal preparation");
    if (status != Status.PENDING || allowance <= 0) {
      return 0;
    }
    if (!stampsCurrent(true)) {
      status = Status.INVALID;
      return 0;
    }
    int initial = allowance;
    var locked = new ArrayList<UpgradeBusBlockEntity>();
    if (!FactoryUpgradeCallbacks.enter(owner)) {
      status = Status.INVALID;
      return 0;
    }
    try {
      for (var source : sources) {
        if (!source.subscriptions.isEmpty() || !source.costs.isEmpty()) {
          if (!source.bus.beginRuntimeNotification(owner)) {
            status = Status.INVALID;
            return initial - allowance;
          }
          locked.add(source.bus);
        }
      }
      while (cursor < steps.size() && allowance > 0) {
        var step = steps.get(cursor);
        allowance--;
        var update = UpgradeRuntimeDataHandlers.prepare(
            step.subscription,
            context(step.subscription, step.source),
            step.source.preparedData.get(step.subscription.definitionId()));
        apply(update, step.subscription, step.source);
        cursor++;
        if (status != Status.PENDING) {
          break;
        }
      }
      if (status == Status.PENDING && cursor == steps.size()) {
        status = Status.READY;
      }
    } catch (RuntimeException failure) {
      status = Status.ERROR;
      ModularMachine.LOGGER.warn("Runtime upgrade preparation failed at {}", owner.getBlockPos(), failure);
    } finally {
      for (int index = locked.size() - 1; index >= 0; index--) {
        locked.get(index).endLifecycleNotification();
      }
      FactoryUpgradeCallbacks.exit(owner);
    }
    if (!stampsCurrent(true)) {
      status = Status.INVALID;
    }

    return initial - allowance;
  }

  private FactoryUpgradeRuntimeData.Context context(UpgradeRuntimeDataHandlers.Subscription subscription, Source source) {
    var items = new ArrayList<FactoryUpgradeRuntimeData.Item>();
    if (source.before != null) {
      for (var slot : subscription.slots()) {
        var before = source.before.get(slot);
        var current = source.prepared.get(slot);
        if (before.isEmpty()) {
          throw new IllegalStateException("Runtime upgrade source disappeared");
        }
        var data = current.isEmpty() ? new CompoundTag() : FactoryUpgradeItemData.read(current);
        items.add(
            new FactoryUpgradeRuntimeData.Item(
                slot,
                before.getItem().builtInRegistryHolder().key().location(),
                before.getCount(),
                current.getCount(),
                current.getDamageValue(),
                current.getMaxDamage(),
                data));
      }
    }
    if (runContext == null) {
      runContext = runs.stream()
          .map(
              run -> new FactoryUpgradeRuntimeData.Run(
                  run.runId(),
                  run.recipeId(),
                  run.progress(),
                  run.duration(),
                  run.parallelInstances(),
                  run.lastProgressTick()))
          .toList();
    }

    return new FactoryUpgradeRuntimeData.Context(
        phase,
        owner.machineId(),
        level.dimension().location(),
        owner.getBlockPos(),
        threadId == null ? "main" : threadId,
        preparedAtTick,
        epoch,
        instances,
        runContext,
        subscription.definitionId(),
        source.position,
        items);
  }

  private void apply(FactoryUpgradeRuntimeData.Update update, UpgradeRuntimeDataHandlers.Subscription subscription, Source source) {
    for (var item : update.items()) {
      if (!subscription.slots().contains(item.slot())) {
        throw new IllegalArgumentException("Runtime handler wrote an unowned slot");
      }
      var stack = source.prepared.get(item.slot());
      if (stack.isEmpty()) {
        throw new IllegalArgumentException("Runtime handler wrote a consumed upgrade");
      }
      if (item.cost().present()) {
        if (!canPay(item.cost(), stack)) {
          status = Status.INSUFFICIENT;
          return;
        }
        source.prepared.set(item.slot(), item.cost().apply(stack, Math.toIntExact(instances)));
        source.changed = true;
        stack = source.prepared.get(item.slot());
      }
      if (!stack.isEmpty() && item.data().isPresent()) {
        source.changed |= UpgradeItemDataHandlers.apply(stack, item.data().orElseThrow());
      }
    }
    if (update.busData().isPresent()) {
      var next = source.preparedData.with(subscription.definitionId(), update.busData().orElseThrow());
      source.changed |= next != source.preparedData;
      source.preparedData = next;
    }
  }

  public boolean current() {
    return status == Status.READY && stampsCurrent(true) && contentsCurrent() && stampsCurrent(true);
  }

  public boolean versionsCurrent() {
    return status == Status.READY && stampsCurrent(true);
  }

  private boolean contentsCurrent() {
    for (var source : sources) {
      if (source.before == null) {
        continue;
      }
      for (int slot = 0; slot < source.before.size(); slot++) {
        var current = source.bus.inventory().getStackInSlot(slot);
        var before = source.before.get(slot);
        if (before.getCount() != current.getCount() || !ItemStack.isSameItemSameComponents(before, current)) {
          return false;
        }
      }
    }

    return true;
  }

  public boolean runsCurrent() {
    ServerThreadGuard.require(level, "Runtime upgrade run identities");
    if (legacy) {
      return true;
    }
    var current = Collections.newSetFromMap(new IdentityHashMap<FrozenItemRun, Boolean>());
    current.addAll(owner.upgradeRuns(threadId));

    return runs.stream().allMatch(current::contains);
  }

  private boolean stampsCurrent(boolean sameTick) {
    ServerThreadGuard.require(level, "Runtime upgrade source proof");
    if (status == Status.INVALID
        || owner.getLevel() != level
        || owner.isRemoved()
        || !level.hasChunkAt(owner.getBlockPos())
        || level.getBlockEntity(owner.getBlockPos()) != owner
        || sameTick && level.getGameTime() != authorityTick
        || UpgradeLifecyclePublisher.isDispatching(owner)
        || owner.structureRegistration() != registration
        || !owner.machineId().equals(machine)
        || FactoryUpgradeService.snapshot().epoch() != epoch
        || !owner.canCommitStructure()
        || !runsCurrent()) {
      return false;
    }
    for (var source : sources) {
      if (!level.hasChunkAt(source.position) || level.getBlockState(source.position) != source.state) {
        return false;
      }
      if (source.bus == null) {
        continue;
      }
      if (level.getBlockEntity(source.position) != source.bus
          || source.bus.upgradeRevision() != source.revision
          || !source.bus.hasFactoryBinding(owner)
          || !source.bus.tickDataReady(owner)
          || phase == FactoryUpgradeRuntimeData.Phase.TICK && source.bus.tickCostSlots(owner) != source.costs
          || source.bus.runtimeDataSubscriptions(owner, phase) != source.subscriptions
          || source.bus.busDataSnapshot() != source.beforeData) {
        return false;
      }
    }

    return true;
  }

  public void acquireInto(List<UpgradeBusBlockEntity.StartCostLease> leases) {
    if (!versionsCurrent()) {
      throw new IllegalStateException("Stale runtime upgrade preparation");
    }
    for (var source : sources) {
      if (source.changed) {
        leases.add(
            source.bus.acquireRuntime(
                owner,
                phase,
                source.revision,
                epoch,
                source.costs,
                source.subscriptions,
                source.prepared,
                source.preparedData));
      }
    }
  }

  public void invalidate() {
    status = Status.INVALID;
  }
}
