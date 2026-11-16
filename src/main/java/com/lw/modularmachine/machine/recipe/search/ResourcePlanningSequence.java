package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.jetbrains.annotations.ApiStatus;

/**
 * Lazy resource planning steps share one budget and retain private continuation
 * state.
 */
@ApiStatus.Internal
public final class ResourcePlanningSequence implements RecipeResourcePlanning {
  private final List<Supplier<RecipeResourcePlanning>> steps;
  private int cursor;
  private RecipeResourcePlanning current;
  private Status status = Status.PENDING;

  public ResourcePlanningSequence(List<Supplier<RecipeResourcePlanning>> steps) {
    this.steps = List.copyOf(steps);
    if (steps.isEmpty()) {
      status = Status.READY;
    }
  }

  @Override
  public Status status() {
    return status;
  }

  @Override
  public Progress advance(int workBudget) {
    if (workBudget < 0) {
      throw new IllegalArgumentException("Negative resource planning budget");
    }
    int used = 0;
    while (status == Status.PENDING && used < workBudget) {
      if (current == null) {
        current = Objects.requireNonNull(steps.get(cursor).get(), "resource planning step");
      }
      if (current.status() == Status.PENDING) {
        var progress = Objects.requireNonNull(current.advance(workBudget - used), "resource planning progress");
        if (progress.workUsed() > workBudget - used || progress.status() != current.status()) {
          throw new IllegalStateException("Resource planner violated its progress contract");
        }
        used += progress.workUsed();
      }
      if (current.status() == Status.PENDING) {
        return new Progress(Status.PENDING, used);
      }
      if (current.status() == Status.REJECTED) {
        status = Status.REJECTED;
      } else {
        cursor++;
        current = null;
        if (cursor == steps.size()) {
          status = Status.READY;
        }
      }
    }

    return new Progress(status, used);
  }
}
