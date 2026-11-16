package com.lw.modularmachine.machine.recipe.resource;

import com.lw.modularmachine.api.recipe.resource.RecipeResourceParticipant;
import com.lw.modularmachine.machine.recipe.PatternInputTransaction;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal prepared writes; acquiring this value never grants script
 * permissions.
 */
@ApiStatus.Internal
public interface PreparedRecipeResource extends RecipeResourceParticipant {
  void acquire();

  default boolean current() {
    return true;
  }

  default void addPatterns(PatternInputTransaction patterns) {
  }
}
