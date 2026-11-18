package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import com.lw.modularmachine.api.recipe.resource.RecipeResourceType;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.recipe.resource.buffered.BufferedRecipeExecution;
import com.lw.modularmachine.machine.recipe.search.index.RequiredInputIndex;
import com.lw.modularmachine.machine.recipe.search.index.RequiredInputQuery;
import com.lw.modularmachine.machine.recipe.search.index.ResourceInputQuery;
import java.util.List;
import java.util.Set;
import java.util.function.ToLongFunction;

final class BufferedInputRecipeSearchResource<D> implements RecipeSearchResource {
  private final ResourceInputQuery query;
  private final RecipeResourceType<D> type;
  private final BufferedRecipeExecution<D> execution;
  private final ToLongFunction<D> quantity;

  BufferedInputRecipeSearchResource(RecipeCatalog catalog, RecipeResourceType<D> type, BufferedRecipeExecution<D> execution, ToLongFunction<D> quantity) {
    this.type = type;
    this.execution = execution;
    this.quantity = quantity;
    var entries = catalog.recipes().candidates().stream()
        .map(candidate -> new RequiredInputIndex.RecipeInputs<String>(candidate.id(), List.of())).toList();
    query = new RequiredInputQuery<>(new RequiredInputIndex<>(entries), Set.of());
  }

  @Override
  public Captured capture(RecipeExecutionTicket ticket, RecipeSearchResources resources) {
    return new Captured() {
      private long available = -1;

      @Override
      public ResourceInputQuery inputs() {
        return query;
      }

      @Override
      public boolean available(String recipeId, MachineRecipe definition) {
        long amount = quantity.applyAsLong(definition.resourceData().get(type));
        if (amount == 0) {
          return true;
        }
        if (available < 0) {
          available = execution.available(ticket);
        }
        return available >= amount;
      }

      @Override
      public boolean inputCurrent(String recipeId, MachineRecipe definition, RecipeResourcePlanning plan) {
        long amount = quantity.applyAsLong(definition.resourceData().get(type));
        return amount == 0 || execution.available(ticket) >= amount;
      }

      @Override
      public RecipeResourcePlanning outputs(String recipeId, MachineRecipe definition) {
        return execution.outputs(ticket, definition.resourceData().get(type));
      }
    };
  }
}
