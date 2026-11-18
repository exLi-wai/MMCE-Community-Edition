package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import com.lw.modularmachine.machine.recipe.resource.environment.EnvironmentRecipeExecution;
import com.lw.modularmachine.machine.recipe.search.index.RequiredInputIndex;
import com.lw.modularmachine.machine.recipe.search.index.RequiredInputQuery;
import com.lw.modularmachine.machine.recipe.search.index.ResourceInputQuery;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;

final class EnvironmentRecipeSearchResource implements RecipeSearchResource {
  private final ResourceInputQuery query;

  EnvironmentRecipeSearchResource(RecipeCatalog catalog) {
    var entries = catalog.recipes().candidates().stream()
        .map(candidate -> new RequiredInputIndex.RecipeInputs<String>(candidate.id(), List.of())).toList();
    query = new RequiredInputQuery<>(new RequiredInputIndex<>(entries), Set.of());
  }

  @Override
  public Captured capture(RecipeExecutionTicket ticket, RecipeSearchResources resources) {
    return new Captured() {
      private List<BlockPos> positions;

      @Override
      public ResourceInputQuery inputs() {
        return query;
      }

      @Override
      public boolean available(String recipeId, MachineRecipe definition) {
        var data = definition.resourceData().get(RecipeResourceDefinitions.ENVIRONMENT);
        if (data.requirements().isEmpty()) {
          return true;
        }
        if (positions == null) {
          positions = EnvironmentRecipeExecution.positions(ticket);
        }
        return EnvironmentRecipeExecution.matches(ticket.level(), positions, data);
      }

      @Override
      public RecipeResourcePlanning outputs(String recipeId, MachineRecipe definition) {
        return new ResourcePlanningSequence(List.of());
      }
    };
  }
}
