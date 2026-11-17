package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.api.recipe.ChemicalRequirement;
import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import com.lw.modularmachine.machine.recipe.ChemicalTransactions;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.recipe.search.index.RequiredInputQuery;
import com.lw.modularmachine.machine.recipe.search.index.ResourceInputQuery;
import com.lw.modularmachine.machine.recipe.search.index.chemical.ChemicalRecipeIndex;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.ApiStatus;

/**
 * Chemical startup anchors retain physical tank checking and per-row selectors.
 */
@ApiStatus.Internal
final class ChemicalRecipeSearchResource implements RecipeSearchResource {
  private final ChemicalRecipeIndex index;

  ChemicalRecipeSearchResource(RecipeCatalog catalog) {
    var ids = catalog.recipes().candidates().stream().map(RecipeCandidate::id).toList();
    var inputs = new HashMap<String, List<ChemicalRequirement>>();
    for (var id : ids) {
      var definition = catalog.definition(id);
      if (definition != null) {
        inputs.put(id, definition.chemicals().inputs());
      }
    }
    index = new ChemicalRecipeIndex(ids, inputs);
  }

  @Override
  public Captured capture(RecipeExecutionTicket ticket, RecipeSearchResources resources) {
    Set<ChemicalResourceKey> keys = index.inputs().hasAnchors() ? resources.chemicalInputKeys(ticket) : Set.of();
    var query = new RequiredInputQuery<>(index.inputs(), keys);

    return new Captured() {
      @Override
      public ResourceInputQuery inputs() {
        return query;
      }

      @Override
      public boolean available(String recipeId, MachineRecipe definition) {
        return definition == null
            || definition.chemicals().inputs().isEmpty()
            || ChemicalTransactions.inputsAvailable(ticket, definition.chemicals().inputs());
      }

      @Override
      public RecipeResourcePlanning outputs(String recipeId, MachineRecipe definition) {
        var planning = new ChemicalOutputLayout(ticket).plan(
            definition == null ? List.of() : definition.chemicals().outputs().stream().filter(row -> row.chance() == 1)
                .toList());

        return new ResourcePlanningAdapter<>(planning::status, planning::advance);
      }
    };
  }
}
