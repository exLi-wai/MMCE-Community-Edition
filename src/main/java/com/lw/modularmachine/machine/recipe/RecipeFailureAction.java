package com.lw.modularmachine.machine.recipe;

/** Progress adjustment when a paid per-tick resource is unavailable. */
public enum RecipeFailureAction {
  RESET("reset"),
  STILL("still"),
  DECREASE("decrease");

  private final String serialized;

  RecipeFailureAction(String serialized) {
    this.serialized = serialized;
  }

  public String serialized() {
    return serialized;
  }

  public static RecipeFailureAction parse(String value) {
    for (var action : values()) {
      if (action.serialized.equals(value)) {
        return action;
      }
    }
    throw new IllegalArgumentException("Unknown failure action: " + value);
  }
}
