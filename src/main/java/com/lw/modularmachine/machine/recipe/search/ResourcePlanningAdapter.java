package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import org.jetbrains.annotations.ApiStatus;

/** Keeps typed layout results inside the resource adapter. */
@ApiStatus.Internal
public final class ResourcePlanningAdapter<K> implements RecipeResourcePlanning {
  private final Supplier<RecipePlanner.Status> status;
  private final IntFunction<RecipePlanner.SearchResult<K>> advance;

  public ResourcePlanningAdapter(Supplier<RecipePlanner.Status> status, IntFunction<RecipePlanner.SearchResult<K>> advance) {
    this.status = status;
    this.advance = advance;
  }

  @Override
  public Status status() {
    return convert(status.get());
  }

  @Override
  public Progress advance(int workBudget) {
    if (workBudget < 0) {
      throw new IllegalArgumentException("Negative resource planning budget");
    }
    if (status() != Status.PENDING) {
      return new Progress(status(), 0);
    }
    var result = advance.apply(workBudget);

    return new Progress(convert(result.status()), result.workUsed());
  }

  private static Status convert(RecipePlanner.Status status) {
    return switch (status) {
      case SELECTED -> Status.READY;
      case NO_MATCH -> Status.REJECTED;
      case BUDGET_EXHAUSTED -> Status.PENDING;
    };
  }
}
