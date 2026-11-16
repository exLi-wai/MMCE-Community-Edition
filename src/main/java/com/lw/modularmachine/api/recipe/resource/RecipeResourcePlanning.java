package com.lw.modularmachine.api.recipe.resource;

import java.util.Objects;

/**
 * Read-only, resumable resource planning; successful planning reserves no
 * inventory.
 */
public interface RecipeResourcePlanning {
  enum Status {
    PENDING,
    READY,
    REJECTED
  }

  record Progress(Status status, int workUsed) {
    public Progress {
      Objects.requireNonNull(status, "status");
      if (workUsed < 0) {
        throw new IllegalArgumentException("Negative resource planning work");
      }
    }
  }

  Status status();

  Progress advance(int workBudget);
}
