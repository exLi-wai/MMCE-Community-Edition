package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.machine.structure.VerificationFailure;
import com.lw.modularmachine.machine.structure.VerificationState;
import com.lw.modularmachine.util.callback.ServerCallbackRegistry;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Read-only post-commit notifications, with opt-in high-frequency events. */
public final class RecipeLifecycleEvents {
  public enum Type {
    STARTED,
    TICK,
    RESOURCE_FAILURE,
    DISCARDED,
    FINISHED,
    MACHINE_TICK
  }

  public enum ResourceFailure {
    ENERGY,
    FLUID
  }

  @FunctionalInterface
  public interface Listener {
    void onEvent(Context context);
  }

  public enum FailureDecision {
    DEFAULT,
    RESET,
    STILL,
    DECREASE,
    DISCARD
  }

  @FunctionalInterface
  public interface FailurePolicy {
    FailureDecision decide(FailureContext context);
  }

  public record FailureContext(
      ResourceLocation dimension,
      BlockPos controllerPosition,
      ResourceLocation machine,
      boolean factory,
      Optional<String> threadId,
      long tick,
      ResourceFailure resource,
      RunSnapshot run) {
    public FailureContext {
      Objects.requireNonNull(dimension, "dimension");
      Objects.requireNonNull(controllerPosition, "controllerPosition");
      Objects.requireNonNull(machine, "machine");
      Objects.requireNonNull(threadId, "threadId");
      Objects.requireNonNull(resource, "resource");
      Objects.requireNonNull(run, "run");
      controllerPosition = controllerPosition.immutable();
      if (tick < 0
          || factory != threadId.isPresent()
          || threadId.isPresent() && threadId.orElseThrow().isBlank()
          || run.state() != FrozenItemRun.State.RUNNING
          || run.lastProgressTick() >= tick) {
        throw new IllegalArgumentException("Invalid recipe failure policy context");
      }
    }
  }

  public record RunSnapshot(
      UUID runId,
      ResourceLocation recipeId,
      FrozenItemRun.State state,
      int parallelInstances,
      int progress,
      int duration,
      long lastProgressTick) {
    public RunSnapshot {
      Objects.requireNonNull(runId, "runId");
      Objects.requireNonNull(recipeId, "recipeId");
      Objects.requireNonNull(state, "state");
      if (parallelInstances < 1
          || duration < 1
          || progress < 0
          || progress > duration
          || state == FrozenItemRun.State.OUTPUT_PENDING && progress != duration) {
        throw new IllegalArgumentException("Invalid recipe event run snapshot");
      }
    }

    static RunSnapshot capture(FrozenItemRun run) {
      return new RunSnapshot(
          run.runId(),
          run.recipeId(),
          run.state(),
          run.parallelInstances(),
          run.progress(),
          run.duration(),
          run.lastProgressTick());
    }
  }

  public record Context(
      Type type,
      ResourceLocation dimension,
      BlockPos controllerPosition,
      ResourceLocation machine,
      boolean factory,
      Optional<String> threadId,
      long tick,
      VerificationState structureState,
      VerificationFailure structureFailure,
      List<RunSnapshot> before,
      List<RunSnapshot> after,
      Optional<ResourceFailure> resourceFailure) {
    public Context {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(dimension, "dimension");
      Objects.requireNonNull(machine, "machine");
      Objects.requireNonNull(threadId, "threadId");
      Objects.requireNonNull(structureState, "structureState");
      Objects.requireNonNull(structureFailure, "structureFailure");
      Objects.requireNonNull(resourceFailure, "resourceFailure");
      controllerPosition = controllerPosition.immutable();
      before = List.copyOf(before);
      after = List.copyOf(after);
      if (tick < 0
          || threadId.isPresent()
              && (!factory || threadId.orElseThrow().isBlank() || threadId.orElseThrow().length() > 64)
          || type != Type.MACHINE_TICK && factory != threadId.isPresent()
          || (type == Type.RESOURCE_FAILURE || type == Type.DISCARDED) != resourceFailure.isPresent()) {
        throw new IllegalArgumentException("Invalid recipe event identity");
      }
      if (type == Type.MACHINE_TICK && (!before.isEmpty() || !after.isEmpty() || threadId.isPresent())
          || type == Type.STARTED
              && (!before.isEmpty()
                  || after.isEmpty()
                  || after.stream().anyMatch(run -> run.progress() != 0 || run.state() != FrozenItemRun.State.RUNNING))
          || type == Type.FINISHED
              && (!after.isEmpty()
                  || before.isEmpty()
                  || before.stream().anyMatch(run -> run.state() != FrozenItemRun.State.OUTPUT_PENDING))
          || type == Type.DISCARDED
              && (!after.isEmpty()
                  || before.isEmpty()
                  || before.stream().anyMatch(run -> run.state() != FrozenItemRun.State.RUNNING))) {
        throw new IllegalArgumentException("Invalid recipe event boundary");
      }
      if (type == Type.TICK || type == Type.RESOURCE_FAILURE) {
        if (before.isEmpty() || before.size() != after.size()) {
          throw new IllegalArgumentException("Unpaired recipe event transition");
        }
        for (int index = 0; index < before.size(); index++) {
          var previous = before.get(index);
          var current = after.get(index);
          if (!previous.runId().equals(current.runId())
              || !previous.recipeId().equals(current.recipeId())
              || previous.parallelInstances() != current.parallelInstances()
              || previous.duration() != current.duration()
              || previous.state() != FrozenItemRun.State.RUNNING
              || current.lastProgressTick() != tick
              || previous.lastProgressTick() >= current.lastProgressTick()
              || type == Type.TICK && current.progress() <= previous.progress()
              || type == Type.RESOURCE_FAILURE
                  && (current.progress() > previous.progress() || current.state() != FrozenItemRun.State.RUNNING)) {
            throw new IllegalArgumentException("Invalid recipe event transition");
          }
        }
      }
      for (var snapshots : List.of(before, after)) {
        var ids = new HashSet<UUID>();
        if (snapshots.stream().anyMatch(run -> !ids.add(run.runId()))) {
          throw new IllegalArgumentException("Duplicate recipe event run");
        }
      }
    }
  }

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

  private RecipeLifecycleEvents() {
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
    return !DELIVERIES.getOrDefault(type, List.of()).isEmpty();
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
        RunSnapshot.capture(run));
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
    if (deliveries.isEmpty() || isDispatching() || !(owner.structureLevel() instanceof ServerLevel level)) {
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
          before.stream().map(RunSnapshot::capture).toList(),
          after.stream().map(RunSnapshot::capture).toList(),
          failure);
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
