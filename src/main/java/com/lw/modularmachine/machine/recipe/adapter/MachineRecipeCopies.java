package com.lw.modularmachine.machine.recipe.adapter;

import com.lw.modularmachine.api.recipe.MachineRecipeDeclaration;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterImport;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Copies the source declaration before any target-specific modifiers are
 * evaluated.
 */
@ApiStatus.Internal
final class MachineRecipeCopies {
  private MachineRecipeCopies() {
  }

  static MachineRecipeDeclaration copy(MachineRecipeDeclaration source, ResourceLocation target, RecipeAdapterImport settings, RecipeAdapterCustomizations additions) {
    int duration = settings.duration() > 0 ? settings.duration() : source.duration();
    int priority = settings.prioritySpecified() ? settings.priority() : source.priority();
    return additions.apply(source, target, priority, duration, settings.energyPerTick());
  }
}
