package com.lw.modularmachine.api.recipe;

/** Paid recipe phase, shared by immutable run snapshots and execution. */
public enum RecipeRunState {
  RUNNING,
  OUTPUT_PENDING
}
