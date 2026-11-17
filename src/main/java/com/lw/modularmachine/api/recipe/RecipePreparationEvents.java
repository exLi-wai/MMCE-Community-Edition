package com.lw.modularmachine.api.recipe;

import com.lw.modularmachine.api.controller.ControllerOwnership;
import com.lw.modularmachine.api.controller.ControllerScriptData;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * Decisions before resource payment. Context values never grant world write
 * access.
 */
public final class RecipePreparationEvents {
  public enum Type {
    PRE_CHECK,
    POST_CHECK,
    PRE_TICK
  }

  public record Context(
      ResourceLocation dimension,
      BlockPos controllerPosition,
      ResourceLocation machine,
      ResourceLocation recipe,
      boolean factory,
      Optional<String> threadId,
      long tick,
      Optional<RecipeLifecycleEvents.RunSnapshot> run,
      ControllerOwnership ownership,
      Optional<ControllerScriptData> scriptData) {
    public Context {
      controllerPosition = controllerPosition.immutable();
    }
  }

  @FunctionalInterface
  public interface Handler {
    void handle(Event event);
  }

  /**
   * Only this invocation's decision is mutable; retained events are sealed on
   * return.
   */
  public static final class Event {
    private final Context context;
    private final Type type;
    private final Thread thread = Thread.currentThread();
    private int parallelism;
    private boolean canceled;
    private String failureReason = "";
    private boolean open = true;

    public Event(Context context, Type type, int parallelism) {
      if (parallelism < 1) {
        throw new IllegalArgumentException("Positive preparation parallelism required");
      }
      this.context = context;
      this.type = type;
      this.parallelism = parallelism;
    }

    public Context getContext() {
      return context;
    }

    public Type type() {
      return type;
    }

    public String getPhase() {
      return type == Type.POST_CHECK ? "END" : "START";
    }

    public int getParallelism() {
      return parallelism;
    }

    public void setParallelism(int value) {
      requireOpen();
      if (type == Type.PRE_TICK || value < 1) {
        throw new IllegalArgumentException("Only recipe checks can lower positive parallelism");
      }
      parallelism = Math.min(parallelism, value);
    }

    public void setFailed(String reason) {
      requireOpen();
      if (reason == null || reason.length() > 512) {
        throw new IllegalArgumentException("Failure reason must be at most 512 characters");
      }
      failureReason = reason;
      canceled = true;
    }

    public void setFailed(boolean destructRecipe, String reason) {
      if (destructRecipe) {
        throw new IllegalArgumentException("Pre-tick handlers cannot destroy paid runs; use a resource failure policy");
      }
      setFailed(reason);
    }

    public void preventProgressing(String reason) {
      setFailed(reason);
    }

    public void setCanceled(boolean value) {
      requireOpen();
      canceled |= value;
    }

    public boolean isCanceled() {
      return canceled;
    }

    public boolean isFailure() {
      return canceled;
    }

    public String getFailureReason() {
      return failureReason;
    }

    public void seal() {
      open = false;
    }

    private void requireOpen() {
      if (!open || Thread.currentThread() != thread) {
        throw new IllegalStateException("Recipe preparation decision is outside its callback");
      }
    }
  }

  private RecipePreparationEvents() {
  }
}
