package com.lw.modularmachine.api.recipe;

import com.lw.modularmachine.api.controller.ControllerOwnership;
import com.lw.modularmachine.api.component.ResourceType;
import com.lw.modularmachine.machine.recipe.RecipeLifecyclePublisher;
import com.lw.modularmachine.api.controller.VerificationFailure;
import com.lw.modularmachine.api.controller.VerificationState;
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

  public record ResourceFailure(ResourceType type) {
    public static final ResourceFailure ENERGY = new ResourceFailure(ResourceType.ENERGY);
    public static final ResourceFailure FLUID = new ResourceFailure(ResourceType.FLUID);
    public static final ResourceFailure CHEMICAL = new ResourceFailure(ResourceType.CHEMICAL);

    public ResourceFailure {
      Objects.requireNonNull(type, "type");
    }

    public static ResourceFailure of(ResourceType type) {
      if (type == ResourceType.ENERGY) {
        return ENERGY;
      }
      if (type == ResourceType.FLUID) {
        return FLUID;
      }
      if (type == ResourceType.CHEMICAL) {
        return CHEMICAL;
      }
      return new ResourceFailure(type);
    }

    public ResourceLocation id() {
      return type.id();
    }

    public String name() {
      return type.name();
    }
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
      RunSnapshot run,
      ControllerOwnership ownership) {
    public FailureContext {
      Objects.requireNonNull(dimension, "dimension");
      Objects.requireNonNull(controllerPosition, "controllerPosition");
      Objects.requireNonNull(machine, "machine");
      Objects.requireNonNull(threadId, "threadId");
      Objects.requireNonNull(resource, "resource");
      Objects.requireNonNull(run, "run");
      Objects.requireNonNull(ownership, "ownership");
      controllerPosition = controllerPosition.immutable();
      if (tick < 0
          || factory != threadId.isPresent()
          || threadId.isPresent() && threadId.orElseThrow().isBlank()
          || run.state() != RecipeRunState.RUNNING
          || run.lastProgressTick() >= tick) {
        throw new IllegalArgumentException("Invalid recipe failure policy context");
      }
    }
  }

  public record RunSnapshot(
      UUID runId,
      ResourceLocation recipeId,
      RecipeRunState state,
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
          || state == RecipeRunState.OUTPUT_PENDING && progress != duration) {
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
      Optional<ResourceFailure> resourceFailure,
      ControllerOwnership ownership) {
    public Context {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(dimension, "dimension");
      Objects.requireNonNull(machine, "machine");
      Objects.requireNonNull(threadId, "threadId");
      Objects.requireNonNull(structureState, "structureState");
      Objects.requireNonNull(structureFailure, "structureFailure");
      Objects.requireNonNull(resourceFailure, "resourceFailure");
      Objects.requireNonNull(ownership, "ownership");
      controllerPosition = controllerPosition.immutable();
      before = List.copyOf(before);
      after = List.copyOf(after);
      boolean reportsShortage = type == Type.RESOURCE_FAILURE || type == Type.DISCARDED;
      String thread = threadId.orElse(null);
      if (tick < 0
          || thread != null && (!factory || thread.isBlank() || thread.length() > 64)
          || type != Type.MACHINE_TICK && factory != threadId.isPresent()
          || reportsShortage != resourceFailure.isPresent()) {
        throw new IllegalArgumentException("Invalid recipe event identity");
      }
      switch (type) {
        case MACHINE_TICK -> requireBoundary(before.isEmpty() && after.isEmpty() && threadId.isEmpty());
        case STARTED -> requireBoundary(
            before.isEmpty()
                && !after.isEmpty()
                && after.stream().allMatch(run -> run.state() == RecipeRunState.RUNNING && run.progress() == 0));
        case FINISHED -> requireBoundary(
            after.isEmpty()
                && !before.isEmpty()
                && before.stream().allMatch(run -> run.state() == RecipeRunState.OUTPUT_PENDING));
        case DISCARDED -> requireBoundary(
            after.isEmpty()
                && !before.isEmpty()
                && before.stream().allMatch(run -> run.state() == RecipeRunState.RUNNING));
        case TICK, RESOURCE_FAILURE -> validateTransitions(type, tick, before, after);
      }
      requireUniqueRunIds(before);
      requireUniqueRunIds(after);
    }

    private static void requireBoundary(boolean valid) {
      if (!valid) {
        throw new IllegalArgumentException("Invalid recipe event boundary");
      }
    }

    private static void validateTransitions(Type type, long tick, List<RunSnapshot> before, List<RunSnapshot> after) {
      if (before.isEmpty() || before.size() != after.size()) {
        throw new IllegalArgumentException("Unpaired recipe event transition");
      }
      for (int index = 0; index < before.size(); index++) {
        var previous = before.get(index);
        var current = after.get(index);
        boolean validProgress = type == Type.TICK ? current.progress() > previous
            .progress() : current.progress() <= previous.progress() && current.state() == RecipeRunState.RUNNING;
        if (!previous.runId().equals(current.runId())
            || !previous.recipeId().equals(current.recipeId())
            || previous.parallelInstances() != current.parallelInstances()
            || previous.duration() != current.duration()
            || previous.state() != RecipeRunState.RUNNING
            || current.lastProgressTick() != tick
            || previous.lastProgressTick() >= tick
            || !validProgress) {
          throw new IllegalArgumentException("Invalid recipe event transition");
        }
      }
    }

    private static void requireUniqueRunIds(List<RunSnapshot> snapshots) {
      if (snapshots.size() < 2) {
        return;
      }
      var ids = new HashSet<UUID>();
      for (var run : snapshots) {
        if (!ids.add(run.runId())) {
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
