package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import com.lw.modularmachine.api.recipe.resource.RecipeResourceType;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.recipe.search.index.RequiredInputIndex;
import com.lw.modularmachine.machine.recipe.search.index.RequiredInputQuery;
import com.lw.modularmachine.machine.recipe.search.index.ResourceInputQuery;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import org.jetbrains.annotations.ApiStatus;

/** Per-candidate exact checks for resources with no pure item/key index. */
@ApiStatus.Internal
public final class CandidateRecipeResource<D> implements RecipeSearchResource {
  private final ResourceInputQuery query;
  private final RecipeResourceType<D> type;
  private final BiPredicate<RecipeExecutionTicket, D> available;
  private final BiFunction<RecipeExecutionTicket, D, RecipeResourcePlanning> outputs;

  public CandidateRecipeResource(RecipeCatalog catalog, RecipeResourceType<D> type, BiPredicate<RecipeExecutionTicket, D> available, BiFunction<RecipeExecutionTicket, D, RecipeResourcePlanning> outputs) {
    this.type = type;
    this.available = available;
    this.outputs = outputs;
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
        return available.test(ticket, definition.resourceData().get(type));
      }

      @Override
      public RecipeResourcePlanning outputs(String recipeId, MachineRecipe definition) {
        return outputs.apply(ticket, definition.resourceData().get(type));
      }
    };
  }
}
