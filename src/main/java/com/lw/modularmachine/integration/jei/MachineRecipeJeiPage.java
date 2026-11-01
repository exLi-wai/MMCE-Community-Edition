package com.lw.modularmachine.integration.jei;

import com.lw.modularmachine.machine.recipe.MachineItemRecipe;
import java.util.List;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;

/** One recipe from one machine uses the legacy component-planned JEI page. */
record MachineRecipeJeiPage(ResourceLocation machine, List<RecipeHolder<MachineItemRecipe>> recipes) {
  static final int RECIPES_PER_PAGE = 1;

  MachineRecipeJeiPage {
    Objects.requireNonNull(machine, "machine");
    recipes = List.copyOf(recipes);
    if (recipes.isEmpty()
        || recipes.size() != RECIPES_PER_PAGE
        || recipes.stream().anyMatch(holder -> !holder.value().machineId().equals(machine))) {
      throw new IllegalArgumentException("Invalid multi-recipe JEI page");
    }
  }

  List<ResourceLocation> sourceIds() {
    return recipes.stream().map(RecipeHolder::id).toList();
  }
}
