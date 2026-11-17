package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.recipe.RecipeItemPayments;
import com.lw.modularmachine.machine.recipe.search.index.RequiredInputIndex;
import com.lw.modularmachine.machine.recipe.search.index.RequiredInputQuery;
import com.lw.modularmachine.machine.recipe.search.index.ResourceInputQuery;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal tool/fuel proof; ordinary exact item indexing retains its anchors.
 */
@ApiStatus.Internal
final class ItemPaymentSearchResource implements RecipeSearchResource {
  private final RequiredInputQuery<String> query;

  ItemPaymentSearchResource(RecipeCatalog catalog) {
    var index = new RequiredInputIndex<>(
        catalog.recipes().candidates().stream()
            .map(candidate -> new RequiredInputIndex.RecipeInputs<String>(candidate.id(), List.of())).toList());
    query = new RequiredInputQuery<>(index, Set.of());
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
        // Item hooks run in the budgeted planning step, after native anchors.
        return true;
      }

      @Override
      public RecipeResourcePlanning outputs(String recipeId, MachineRecipe definition) {
        return new RecipeResourcePlanning() {
          private Status status = Status.PENDING;
          @Override
          public Status status() {
            return status;
          }
          @Override
          public Progress advance(int workBudget) {
            if (status == Status.PENDING && workBudget > 0) {
              status = RecipeItemPayments.available(ticket, definition, 1, false) ? Status.READY : Status.REJECTED;
              return new Progress(status, 1);
            }
            return new Progress(status, 0);
          }
        };
      }
    };
  }
}
