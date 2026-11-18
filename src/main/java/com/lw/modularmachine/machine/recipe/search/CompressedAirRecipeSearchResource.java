package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import com.lw.modularmachine.machine.recipe.resource.pneumatic.CompressedAirRecipeExecution;
import com.lw.modularmachine.machine.recipe.search.index.RequiredInputIndex;
import com.lw.modularmachine.machine.recipe.search.index.RequiredInputQuery;
import com.lw.modularmachine.machine.recipe.search.index.ResourceInputQuery;
import java.util.List;
import java.util.Set;

final class CompressedAirRecipeSearchResource implements RecipeSearchResource {
  private final ResourceInputQuery query;

  CompressedAirRecipeSearchResource(RecipeCatalog catalog) {
    var entries = catalog.recipes().candidates().stream()
        .map(candidate -> new RequiredInputIndex.RecipeInputs<String>(candidate.id(), List.of())).toList();
    query = new RequiredInputQuery<>(new RequiredInputIndex<>(entries), Set.of());
  }

  @Override
  public Captured capture(RecipeExecutionTicket ticket, RecipeSearchResources resources) {
    return new Captured() {
      @Override
      public ResourceInputQuery inputs() {
        return query;
      }

      @Override
      public boolean available(String recipeId, MachineRecipe definition) {
        return CompressedAirRecipeExecution
            .available(ticket, definition.resourceData().get(RecipeResourceDefinitions.COMPRESSED_AIR));
      }

      @Override
      public RecipeResourcePlanning outputs(String recipeId, MachineRecipe definition) {
        return CompressedAirRecipeExecution
            .searchOutputs(ticket, definition.resourceData().get(RecipeResourceDefinitions.COMPRESSED_AIR));
      }
    };
  }
}
