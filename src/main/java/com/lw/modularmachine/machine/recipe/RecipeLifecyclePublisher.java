package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.recipe.RecipeFailureAction;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.machine.controller.ControllerOwnershipService;
import com.lw.modularmachine.machine.controller.ControllerOwnershipSubject;
import com.lw.modularmachine.machine.recipe.command.RecipeCommandService;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.machine.structure.VerificationFailure;
import com.lw.modularmachine.machine.structure.VerificationState;
import com.lw.modularmachine.util.callback.ServerCallbackRegistry;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import com.lw.modularmachine.api.recipe.RecipeLifecycleEvents.Context;
import com.lw.modularmachine.api.recipe.RecipeLifecycleEvents.FailureContext;
import com.lw.modularmachine.api.recipe.RecipeLifecycleEvents.FailureDecision;
import com.lw.modularmachine.api.recipe.RecipeLifecycleEvents.FailurePolicy;
import com.lw.modularmachine.api.recipe.RecipeLifecycleEvents.Listener;
import com.lw.modularmachine.api.recipe.RecipeLifecycleEvents.ResourceFailure;
import com.lw.modularmachine.api.recipe.RecipeLifecycleEvents.RunSnapshot;
import com.lw.modularmachine.api.recipe.RecipeLifecycleEvents.Type;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.jetbrains.annotations.ApiStatus;

/** Internal production dispatch and callback guards; not an integration API. */
@ApiStatus.Internal
public final class RecipeLifecyclePublisher {
  private record Handler(Set<Type> types, Listener listener) {
    Handler {
      types = Set.copyOf(types);
      Objects.requireNonNull(listener, "listener");
      if (types.isEmpty()) {
        throw new IllegalArgumentException("Empty recipe event subscription");
      }
    }
  }

  private static final ServerCallbackRegistry<Handler> HANDLERS = new ServerCallbackRegistry<>(() -> {
  });
  private static final ServerCallbackRegistry<FailurePolicy> FAILURE_POLICIES = new ServerCallbackRegistry<>(() -> {
  });
  private static final Map<Type, List<Map.Entry<ResourceLocation, Handler>>> DELIVERIES = new EnumMap<>(Type.class);
  private static final Map<ResourceLocation, Listener> REPORTED_FAILURES = new HashMap<>();
  private static final Map<ResourceLocation, FailurePolicy> REPORTED_POLICY_FAILURES = new HashMap<>();
  private static final Map<StructureService.StructureSubject, Long> MACHINE_TICKS = new WeakHashMap<>();
  private static StructureService.StructureSubject activeOwner;

  private RecipeLifecyclePublisher() {
  }

  public static void register(ResourceLocation id, Listener listener) {
    register(id, Set.of(Type.STARTED, Type.FINISHED), listener);
  }

  public static void register(ResourceLocation id, Set<Type> types, Listener listener) {
    ServerCallbackRegistry.requireServer();
    var replacement = new Handler(types, listener);
    var previous = HANDLERS.find(id);
    if (previous != null && previous.listener() == listener && previous.types().equals(replacement.types())) {
      return;
    }
    HANDLERS.register(id, replacement);
    REPORTED_FAILURES.remove(id);
    rebuildDeliveries();
  }

  public static void remove(ResourceLocation id) {
    if (HANDLERS.remove(id)) {
      REPORTED_FAILURES.remove(id);
      rebuildDeliveries();
    }
  }

  public static void invalidate(ResourceLocation id) {
    HANDLERS.invalidate(id);
    REPORTED_FAILURES.remove(id);
  }

  public static void registerFailurePolicy(ResourceLocation id, FailurePolicy policy) {
    if (FAILURE_POLICIES.register(id, policy)) {
      REPORTED_POLICY_FAILURES.remove(id);
    }
  }

  public static void removeFailurePolicy(ResourceLocation id) {
    if (FAILURE_POLICIES.remove(id)) {
      REPORTED_POLICY_FAILURES.remove(id);
    }
  }

  public static void invalidateFailurePolicy(ResourceLocation id) {
    FAILURE_POLICIES.invalidate(id);
    REPORTED_POLICY_FAILURES.remove(id);
  }

  private static void rebuildDeliveries() {
    for (var type : Type.values()) {
      DELIVERIES.put(
          type,
          HANDLERS.orderedSnapshot().stream().filter(entry -> entry.getValue().types().contains(type)).toList());
    }
  }

  public static boolean hasListeners(Type type) {
    return !DELIVERIES.getOrDefault(type, List.of()).isEmpty()
        || RecipeCommandService.hasActions(type)
        || type == Type.FINISHED
        || type == Type.DISCARDED;
  }

  public static boolean hasFailurePolicies() {
    return !FAILURE_POLICIES.isEmpty();
  }

  public static boolean isDispatching() {
    return activeOwner != null;
  }

  public static boolean isDispatching(StructureService.StructureSubject controller) {
    return activeOwner != null && activeOwner == controller;
  }

  public static void started(MachineControllerBlockEntity owner, FrozenItemRun run) {
    dispatch(owner, Optional.empty(), Type.STARTED, List.of(), List.of(run), Optional.empty());
  }

  public static void finished(MachineControllerBlockEntity owner, FrozenItemRun run) {
    dispatch(owner, Optional.empty(), Type.FINISHED, List.of(run), List.of(), Optional.empty());
  }

  public static void advanced(MachineControllerBlockEntity owner, FrozenItemRun before, FrozenItemRun after) {
    dispatch(owner, Optional.empty(), Type.TICK, List.of(before), List.of(after), Optional.empty());
  }

  public static void resourceFailed(MachineControllerBlockEntity owner, FrozenItemRun before, FrozenItemRun after, ResourceFailure failure) {
    dispatch(owner, Optional.empty(), Type.RESOURCE_FAILURE, List.of(before), List.of(after), Optional.of(failure));
  }

  public static void resourceDiscarded(MachineControllerBlockEntity owner, FrozenItemRun run, ResourceFailure failure) {
    dispatch(owner, Optional.empty(), Type.DISCARDED, List.of(run), List.of(), Optional.of(failure));
  }

  public static void started(FactoryControllerBlockEntity owner, String threadId, List<FrozenItemRun> runs) {
    dispatch(owner, Optional.of(threadId), Type.STARTED, List.of(), runs, Optional.empty());
  }

  public static void finished(FactoryControllerBlockEntity owner, String threadId, List<FrozenItemRun> runs) {
    dispatch(owner, Optional.of(threadId), Type.FINISHED, runs, List.of(), Optional.empty());
  }

  public static void advanced(FactoryControllerBlockEntity owner, String threadId, List<FrozenItemRun> before, List<FrozenItemRun> after) {
    dispatch(owner, Optional.of(threadId), Type.TICK, before, after, Optional.empty());
  }

  public static void resourceFailed(FactoryControllerBlockEntity owner, String threadId, List<FrozenItemRun> before, List<FrozenItemRun> after, ResourceFailure failure) {
    dispatch(owner, Optional.of(threadId), Type.RESOURCE_FAILURE, before, after, Optional.of(failure));
  }

  public static void resourceDiscarded(FactoryControllerBlockEntity owner, String threadId, List<FrozenItemRun> runs, ResourceFailure failure) {
    if (!runs.isEmpty()) {
      dispatch(owner, Optional.of(threadId), Type.DISCARDED, runs, List.of(), Optional.of(failure));
    }
  }

  /**
   * Runs before applying the frozen recipe action; policies only choose a bounded
   * decision.
   */
  public static FailureDecision decideResourceFailure(StructureService.StructureSubject owner, Optional<String> threadId, FrozenItemRun run, ResourceFailure resource) {
    if (FAILURE_POLICIES.isEmpty() || isDispatching() || !(owner.structureLevel() instanceof ServerLevel level)) {
      return FailureDecision.DEFAULT;
    }
    ServerThreadGuard.require(level, "Recipe failure policy");
    if (owner.structureRemoved()
        || !level.hasChunkAt(owner.structurePosition())
        || level.getBlockEntity(owner.structurePosition()) != owner) {
      return FailureDecision.DEFAULT;
    }
    var context = new FailureContext(
        level.dimension().location(),
        owner.structurePosition(),
        owner.structureMachineId(),
        owner instanceof FactoryControllerBlockEntity,
        threadId,
        level.getGameTime(),
        resource,
        snapshot(run),
        ControllerOwnershipService.snapshot(level, (ControllerOwnershipSubject) owner));
    var policySnapshot = FAILURE_POLICIES.snapshot();
    var deliveries = FAILURE_POLICIES.orderedSnapshot();
    activeOwner = owner;
    try {
      for (var delivery : deliveries) {
        if (FAILURE_POLICIES.snapshot() != policySnapshot) {
          return FailureDecision.DEFAULT;
        }
        try {
          var decision = Objects.requireNonNull(delivery.getValue().decide(context), "Failure policy decision");
          if (FAILURE_POLICIES.snapshot() != policySnapshot) {
            return FailureDecision.DEFAULT;
          }
          if (decision != FailureDecision.DEFAULT) {
            return decision;
          }
        } catch (RuntimeException failure) {
          var policy = delivery.getValue();
          if (FAILURE_POLICIES.find(delivery.getKey()) == policy
              && REPORTED_POLICY_FAILURES.get(delivery.getKey()) != policy) {
            REPORTED_POLICY_FAILURES.put(delivery.getKey(), policy);
            ModularMachine.LOGGER.warn(
                "Recipe failure policy {} failed for {} at {}",
                delivery.getKey(),
                resource,
                owner.structurePosition(),
                failure);
          }
        }
      }
    } finally {
      activeOwner = null;
    }

    return FailureDecision.DEFAULT;
  }

  public static FrozenItemRun applyFailureDecision(FrozenItemRun run, long tick, FailureDecision decision) {
    return switch (decision) {
      case DEFAULT -> run.afterResourceFailure(tick);
      case RESET -> run.afterResourceFailure(tick, RecipeFailureAction.RESET);
      case STILL -> run.afterResourceFailure(tick, RecipeFailureAction.STILL);
      case DECREASE -> run.afterResourceFailure(tick, RecipeFailureAction.DECREASE);
      case DISCARD -> throw new IllegalArgumentException("Discard decisions remove the run owner instead");
    };
  }

  public static void machineTick(StructureService.StructureSubject owner) {
    if (!hasListeners(Type.MACHINE_TICK) || isDispatching() || !(owner.structureLevel() instanceof ServerLevel level)) {
      return;
    }
    ServerThreadGuard.require(level, "Machine tick notification");
    long tick = level.getGameTime();
    if (Objects.equals(MACHINE_TICKS.get(owner), tick)) {
      return;
    }
    MACHINE_TICKS.put(owner, tick);
    dispatch(owner, Optional.empty(), Type.MACHINE_TICK, List.of(), List.of(), Optional.empty());
  }

  private static void dispatch(StructureService.StructureSubject owner, Optional<String> threadId, Type type, List<FrozenItemRun> before, List<FrozenItemRun> after, Optional<ResourceFailure> failure) {
    var deliveries = DELIVERIES.getOrDefault(type, List.of());
    if (deliveries.isEmpty() && !RecipeCommandService.wantsCapture(owner, type)
        || isDispatching()
        || !(owner.structureLevel() instanceof ServerLevel level)) {
      return;
    }
    ServerThreadGuard.require(level, "Recipe lifecycle event");
    if (owner.structureRemoved()
        || !level.hasChunkAt(owner.structurePosition())
        || level.getBlockEntity(owner.structurePosition()) != owner) {
      return;
    }
    try {
      VerificationState state;
      VerificationFailure structureFailure;
      if (owner instanceof MachineControllerBlockEntity ordinary) {
        state = ordinary.verificationState();
        structureFailure = ordinary.verificationFailure();
      } else if (owner instanceof FactoryControllerBlockEntity factory) {
        state = factory.verificationState();
        structureFailure = factory.verificationFailure();
      } else {
        throw new IllegalArgumentException("Unsupported recipe event controller");
      }
      var context = new Context(
          type,
          level.dimension().location(),
          owner.structurePosition(),
          owner.structureMachineId(),
          owner instanceof FactoryControllerBlockEntity,
          threadId,
          level.getGameTime(),
          state,
          structureFailure,
          before.stream().map(RecipeLifecyclePublisher::snapshot).toList(),
          after.stream().map(RecipeLifecyclePublisher::snapshot).toList(),
          failure,
          ControllerOwnershipService.snapshot(level, (ControllerOwnershipSubject) owner));
      RecipeCommandService.capture(owner, context);
      activeOwner = owner;
      try {
        for (var delivery : deliveries) {
          try {
            delivery.getValue().listener().onEvent(context);
          } catch (RuntimeException error) {
            var listener = delivery.getValue().listener();
            if (HANDLERS.find(delivery.getKey()) == delivery.getValue()
                && REPORTED_FAILURES.get(delivery.getKey()) != listener) {
              REPORTED_FAILURES.put(delivery.getKey(), listener);
              ModularMachine.LOGGER.warn(
                  "Recipe {} event failed for {} at {}",
                  type,
                  delivery.getKey(),
                  owner.structurePosition(),
                  error);
            }
          }
        }
      } finally {
        activeOwner = null;
      }
    } catch (RuntimeException captureFailure) {
      ModularMachine.LOGGER
          .warn("Recipe {} event capture failed at {}", type, owner.structurePosition(), captureFailure);
    }
  }

  private static RunSnapshot snapshot(FrozenItemRun run) {
    return new RunSnapshot(
        run.runId(),
        run.recipeId(),
        run.state(),
        run.parallelInstances(),
        run.progress(),
        run.duration(),
        run.lastProgressTick());
  }

  @SubscribeEvent
  public static void stopped(ServerStoppedEvent event) {
    HANDLERS.clear();
    FAILURE_POLICIES.clear();
    DELIVERIES.clear();
    REPORTED_FAILURES.clear();
    REPORTED_POLICY_FAILURES.clear();
    MACHINE_TICKS.clear();
    activeOwner = null;
  }
}
