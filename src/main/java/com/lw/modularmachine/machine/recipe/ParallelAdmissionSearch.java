package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import java.util.Objects;
import java.util.function.IntFunction;
import org.jetbrains.annotations.ApiStatus;

/** Binary count search over monotone, read-only resource planning probes. */
@ApiStatus.Internal
final class ParallelAdmissionSearch {
  private final IntFunction<RecipeResourcePlanning> prepare;
  private int low;
  private int high;
  private int probe;
  private RecipeResourcePlanning planning;
  private int remainingBudget;

  ParallelAdmissionSearch(int maximum, IntFunction<RecipeResourcePlanning> prepare) {
    high = Math.max(0, maximum);
    this.prepare = Objects.requireNonNull(prepare, "prepare");
  }

  int advance(int budget) {
    if (budget < 0) {
      throw new IllegalArgumentException("Negative parallel admission budget");
    }
    remainingBudget = budget;
    while (low < high) {
      if (planning == null) {
        if (remainingBudget == 0) {
          return -1;
        }
        remainingBudget--;
        probe = low + (int) (((long) high - low + 1) / 2);
        planning = Objects.requireNonNull(prepare.apply(probe), "parallel resource probe");
      }
      if (planning.status() == RecipeResourcePlanning.Status.PENDING) {
        var progress = planning.advance(remainingBudget);
        if (progress.workUsed() > remainingBudget || progress.status() != planning.status()) {
          throw new IllegalStateException("Parallel admission violated its progress contract");
        }
        remainingBudget -= progress.workUsed();
      }
      if (planning.status() == RecipeResourcePlanning.Status.PENDING) {
        return -1;
      }
      if (planning.status() == RecipeResourcePlanning.Status.READY) {
        low = probe;
      } else {
        high = probe - 1;
      }
      planning = null;
    }

    return low;
  }

  int remainingBudget() {
    return remainingBudget;
  }
}
