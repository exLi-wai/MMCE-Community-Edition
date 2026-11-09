package com.lw.modularmachine.api.controller;

import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.machine.controller.ControllerScriptDataValues;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable controller-owned script values, independent from upgrades and paid
 * runs.
 */
public record ControllerScriptData(
    long revision,
    String customData,
    Map<ResourceLocation, RecipeModifier> customModifiers,
    UUID stateId) {
  public ControllerScriptData(long revision, String customData, Map<ResourceLocation, RecipeModifier> customModifiers) {
    this(revision, customData, customModifiers, UUID.randomUUID());
  }

  public ControllerScriptData {
    Objects.requireNonNull(stateId, "stateId");
    if (revision < 0) {
      throw new IllegalArgumentException("Negative controller script revision");
    }
    customData = ControllerScriptDataValues.canonicalData(customData);
    customModifiers = ControllerScriptDataValues.modifiers(customModifiers);
  }
}
