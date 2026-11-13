package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import com.lw.modularmachine.machine.recipe.RecipeStartPlan;
import com.lw.modularmachine.machine.recipe.MachineRecipeService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.Recipe;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeEvents;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeRecipeChecks.Checker;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeRecipeChecks.Context;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeRecipeChecks.Decision;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeSnapshot;
import org.jetbrains.annotations.ApiStatus;

/** Internal budgeted recipe-check scopes, source certificates and registry. */
@ApiStatus.Internal
public final class UpgradeRecipeChecks {
  public enum Status {
    ALLOWED,
    REJECTED,
    PENDING,
    STALE,
    ERROR
  }

  public record Result(Status status, int workUsed) {
    public Result {
      Objects.requireNonNull(status, "status");
      if (workUsed < 0) {
        throw new IllegalArgumentException("Negative recipe check work");
      }
    }
  }

  public record Subscription(ResourceLocation handlerId, FactoryUpgradeEvents.Upgrade upgrade) {
    public Subscription {
      Objects.requireNonNull(handlerId, "handlerId");
      Objects.requireNonNull(upgrade, "upgrade");
    }
  }

  public static final class FailedException extends IllegalStateException {
    public FailedException() {
      super("Upgrade recipe check failed");
    }
  }

  public static final class StaleException extends IllegalStateException {
    public StaleException() {
      super("Upgrade recipe check lost its source versions");
    }
  }

  private record Source(UpgradeBusBlockEntity bus, long revision, FactoryUpgradeCatalog catalog) {
  }

  private record Call(Checker checker, FactoryUpgradeEvents.Upgrade upgrade) {
  }

  private static final FactoryUpgradeHandlers<Checker> HANDLERS = new FactoryUpgradeHandlers<>();

  private UpgradeRecipeChecks() {
  }

  public static void register(ResourceLocation id, Checker checker) {
    HANDLERS.register(id, checker);
  }

  public static void remove(ResourceLocation id) {
    HANDLERS.remove(id);
  }

  public static void invalidate(ResourceLocation id) {
    HANDLERS.invalidate(id);
  }

  public static boolean isRegistered(ResourceLocation id) {
    return checker(id) != null;
  }

  private static Checker checker(ResourceLocation id) {
    var host = HANDLERS.find(id);

    return host == null ? FactoryUpgradeKubeJSHandlers.recipeCheck(id) : host;
  }

  public static Scope capture(UpgradeBusOwner factory, String threadId) {
    FactoryUpgradeHandlers.requireServer();

    return new Scope(factory, threadId);
  }

  static void clearOnServerStop() {
    HANDLERS.clear();
  }

  /**
   * Search-local source proof, including invalid but bound required checker
   * buses.
   */
  public static final class Scope {
    private final UpgradeBusOwner factory;
    private final String threadId;
    private final ResourceLocation machine;
    private final ResourceLocation lockedRecipe;
    private final boolean enabled;
    private final FactoryUpgradeSnapshot upgrades;
    private final Object registration;
    private final long epoch;
    private final List<Source> sources;
    private final List<Call> calls;
    private final boolean invalid;

    private Scope(UpgradeBusOwner factory, String threadId) {
      this.factory = Objects.requireNonNull(factory, "factory");
      this.threadId = Objects.requireNonNull(threadId, "threadId");
      var thread = thread();
      machine = factory.machineId();
      lockedRecipe = thread == null ? null : thread.lockedRecipe();
      enabled = thread != null && thread.enabled();
      upgrades = thread == null ? FactoryUpgradeSnapshot.identity(0) : thread.upgradeSnapshot();
      registration = factory.structureRegistration();
      epoch = FactoryUpgradeService.snapshot().epoch();
      var captured = new ArrayList<Source>();
      var pending = new ArrayList<Call>();
      boolean failed = thread == null;
      for (var bus : factory.upgradeCallbackBuses()) {
        captured.add(new Source(bus, bus.upgradeRevision(), bus.upgradeCatalog(factory)));
        if ((bus.recipeCheckRequired(factory)
            || bus.recipeModifiersRequired(factory)
            || bus.startDataRequired(factory)
            || bus.startBusDataRequired(factory)
            || bus.runtimeDataRequired(factory)) && !bus.upgradeCatalogValid(factory)) {
          failed = true;
        }
        for (var subscription : bus.recipeCheckSubscriptions(factory)) {
          var checker = checker(subscription.handlerId());
          if (checker == null) {
            failed = true;
          } else {
            pending.add(new Call(checker, subscription.upgrade()));
          }
        }
      }
      sources = List.copyOf(captured);
      calls = List.copyOf(pending);
      invalid = failed;
    }

    private UpgradeStartState thread() {
      return factory.upgradeStartState(threadId);
    }

    public boolean matches(UpgradeBusOwner owner, String id) {
      return factory == owner && threadId.equals(id);
    }

    public boolean current() {
      FactoryUpgradeHandlers.requireServer();
      if (!(factory.getLevel() instanceof ServerLevel level)
          || factory.isRemoved()
          || !level.hasChunkAt(factory.getBlockPos())
          || level.getBlockEntity(factory.getBlockPos()) != factory) {
        return false;
      }
      var thread = thread();
      if (FactoryUpgradeService.snapshot().epoch() != epoch
          || thread == null
          || thread.enabled() != enabled
          || !Objects.equals(thread.lockedRecipe(), lockedRecipe)
          || !thread.upgradeSnapshot().equals(upgrades)
          || !machine.equals(factory.machineId())
          || factory.structureRegistration() != registration
          || !factory.canCommitStructure()
          || !factory.upgradeRunIdle(threadId)) {
        return false;
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

    public Session begin(RecipeStartPlan plan) {
      return new Session(this, plan);
    }
  }

  public static final class Session {
    private final Scope scope;
    private final Context context;
    private int cursor;
    private Status status;

    private Session(Scope scope, RecipeStartPlan plan) {
      this.scope = scope;
      Context prepared = null;
      status = scope.invalid ? Status.ERROR : scope.calls.isEmpty() ? Status.ALLOWED : Status.PENDING;
      if (status == Status.PENDING) {
        try {
          var recipe = MachineRecipeService.definitionForStart(scope.factory, plan).orElseThrow();
          var level = (ServerLevel) scope.factory.getLevel();
          var encoded = Recipe.CODEC
              .encodeStart(level.registryAccess().createSerializationContext(NbtOps.INSTANCE), recipe).getOrThrow();
          if (!(encoded instanceof CompoundTag definition)) {
            throw new IllegalArgumentException("Recipe check definition did not encode as a compound");
          }
          prepared = new Context(
              scope.machine,
              ResourceLocation.parse(plan.recipe().id()),
              level.dimension().location(),
              scope.factory.getBlockPos(),
              scope.threadId,
              scope.epoch,
              scope.upgrades,
              definition);
        } catch (RuntimeException failure) {
          status = Status.ERROR;
          ModularMachine.LOGGER.warn("Upgrade recipe check context failed at {}", scope.factory.getBlockPos(), failure);
        }
      }
      context = prepared;
    }

    public boolean admitted(UpgradeBusOwner factory, String threadId) {
      return status == Status.ALLOWED && scope.matches(factory, threadId) && scope.current();
    }

    public Result advance(int budget) {
      FactoryUpgradeHandlers.requireServer();
      if (budget < 0) {
        throw new IllegalArgumentException("Negative recipe check budget");
      }
      if (!scope.current()) {
        return new Result(Status.STALE, 0);
      }
      if (status != Status.PENDING || budget == 0) {
        return new Result(status, 0);
      }
      if (!FactoryUpgradeCallbacks.enter(scope.factory)) {
        return new Result(Status.STALE, 0);
      }
      var locked = new ArrayList<UpgradeBusBlockEntity>();
      int used = 0;
      try {
        for (var source : scope.sources) {
          if (source.bus().upgradeCatalogValid(scope.factory)) {
            if (!source.bus().beginLifecycleNotification(scope.factory)) {
              return new Result(Status.STALE, 0);
            }
            locked.add(source.bus());
          }
        }
        while (cursor < scope.calls.size() && used < budget) {
          var call = scope.calls.get(cursor);
          used++;
          var decision = Objects.requireNonNull(call.checker().check(context, call.upgrade()), "Recipe check result");
          if (FactoryUpgradeService.snapshot().epoch() != scope.epoch) {
            return new Result(Status.STALE, used);
          }
          cursor++;
          if (decision == Decision.REJECT) {
            status = Status.REJECTED;
            break;
          }
        }
        if (status == Status.PENDING && cursor == scope.calls.size()) {
          status = Status.ALLOWED;
        }
      } catch (RuntimeException failure) {
        status = Status.ERROR;
        ModularMachine.LOGGER
            .warn("Upgrade recipe check failed at {} for {}", scope.factory.getBlockPos(), context.recipeId(), failure);
      } finally {
        for (int index = locked.size() - 1; index >= 0; index--) {
          locked.get(index).endLifecycleNotification();
        }
        FactoryUpgradeCallbacks.exit(scope.factory);
      }

      return new Result(scope.current() ? status : Status.STALE, used);
    }
  }
}
