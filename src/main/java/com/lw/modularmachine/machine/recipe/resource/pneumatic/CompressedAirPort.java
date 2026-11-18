package com.lw.modularmachine.machine.recipe.resource.pneumatic;

import com.lw.modularmachine.api.recipe.resource.RecipeResourceParticipant;
import org.jetbrains.annotations.ApiStatus;

/**
 * Live access to a single native air handler. Quantities are not duplicated in
 * recipe storage.
 */
@ApiStatus.Internal
public interface CompressedAirPort {
  int air();

  int volume();

  int outputSpace();

  RecipeResourceParticipant acquireAirChange(int expected, int delta);

  void publishAirChange();
}
