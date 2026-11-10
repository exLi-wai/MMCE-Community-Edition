package com.lw.modularmachine.api.recipe.adapter;

import com.lw.modularmachine.api.recipe.MachineRecipeBuilder;
import java.util.List;

/**
 * Converts the selected source recipes into new native machine declarations.
 * Implementations use the supplied registry view and detached builders; they
 * must not mutate source recipes, world objects or inventory.
 */
@FunctionalInterface
public interface MachineRecipeAdapter {
  List<MachineRecipeBuilder> adapt(RecipeAdapterContext context);
}
