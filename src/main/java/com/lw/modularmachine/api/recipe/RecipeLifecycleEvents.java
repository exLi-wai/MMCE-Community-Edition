package com.lw.modularmachine.api.recipe;

import com.lw.modularmachine.machine.recipe.FrozenItemRun;
import com.lw.modularmachine.machine.recipe.RecipeLifecyclePublisher;
import com.lw.modularmachine.machine.structure.VerificationFailure;
import com.lw.modularmachine.machine.structure.VerificationState;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

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

  private RecipeLifecycleEvents() {
  }

  public static void register(ResourceLocation id, Listener listener) {
    RecipeLifecyclePublisher.register(id, listener);
  }

  public static void register(ResourceLocation id, Set<Type> types, Listener listener) {
    RecipeLifecyclePublisher.register(id, types, listener);
  }

  public static void remove(ResourceLocation id) {
    RecipeLifecyclePublisher.remove(id);
  }

  public static void invalidate(ResourceLocation id) {
    RecipeLifecyclePublisher.invalidate(id);
  }

  public static void registerFailurePolicy(ResourceLocation id, FailurePolicy policy) {
    RecipeLifecyclePublisher.registerFailurePolicy(id, policy);
  }

  public static void removeFailurePolicy(ResourceLocation id) {
    RecipeLifecyclePublisher.removeFailurePolicy(id);
  }

  public static void invalidateFailurePolicy(ResourceLocation id) {
    RecipeLifecyclePublisher.invalidateFailurePolicy(id);
  }
}
