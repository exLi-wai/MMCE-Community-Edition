package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.recipe.search.index.ResourceInputQuery;
import org.jetbrains.annotations.ApiStatus;

/**
 * Catalog-owned resource adaptation; capture and exact checks run on the server
 * thread.
 */
@ApiStatus.Internal
public interface RecipeSearchResource {
  Captured capture(RecipeExecutionTicket ticket, RecipeSearchResources resources);

  /**
   * One input group's query and planning state; it never reserves physical
   * resources.
   */
  interface Captured {
    ResourceInputQuery inputs();

    boolean available(String recipeId, MachineRecipe definition);

    RecipeResourcePlanning outputs(String recipeId, MachineRecipe definition);
  }
}
