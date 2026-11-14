package com.lw.modularmachine.api.recipe.adapter;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Immutable, machine-local selection and costs for one adapter import. */
public record RecipeAdapterImport(
    String id,
    ResourceLocation adapter,
    List<ResourceLocation> recipes,
    boolean allRecipes,
    int duration,
    long energyPerTick,
    int priority,
    RecipeAdapterCustomization customization) {
  public RecipeAdapterImport(String id, ResourceLocation adapter, List<ResourceLocation> recipes, boolean allRecipes, int duration, long energyPerTick, int priority) {
    this(id, adapter, recipes, allRecipes, duration, energyPerTick, priority, RecipeAdapterCustomization.NONE);
  }

  public RecipeAdapterImport {
    Objects.requireNonNull(adapter, "adapter");
    Objects.requireNonNull(customization, "customization");
    recipes = List.copyOf(new LinkedHashSet<>(Objects.requireNonNull(recipes, "recipes")));
    if (id == null || !id.matches("[a-zA-Z0-9_.-]{1,64}") || duration < 0 || energyPerTick < 0) {
      throw new IllegalArgumentException("Invalid recipe adapter import");
    }
    if (allRecipes && !recipes.isEmpty()) {
      throw new IllegalArgumentException("Selecting all source recipes cannot also declare an explicit source list");
    }
  }
}
