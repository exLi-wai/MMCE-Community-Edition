package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import com.lw.modularmachine.api.recipe.FluidRequirement;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.recipe.search.index.ResourceInputQuery;
import com.lw.modularmachine.machine.recipe.search.index.fluid.FluidRecipeIndex;
import java.util.List;
import org.jetbrains.annotations.ApiStatus;

/**
 * Fluid identity coarse filter, component/count checks and exact output layout.
 */
@ApiStatus.Internal
final class FluidRecipeSearchResource implements RecipeSearchResource {
  private final RecipeCatalog catalog;
  private final FluidRecipeIndex index;

  FluidRecipeSearchResource(RecipeCatalog catalog) {
    this.catalog = catalog;
    var ids = catalog.recipes().candidates().stream().map(RecipeCandidate::id).toList();
    index = new FluidRecipeIndex(ids, catalog::fluidInputRequirements, catalog::selectors);
  }

  @Override
  public Captured capture(RecipeExecutionTicket ticket, RecipeSearchResources resources) {
    var fluids = resources.fluids(ticket);
    var query = fluids.inputQuery(index);

    return new Captured() {
      private FluidOutputLayout layout;

      @Override
      public ResourceInputQuery inputs() {
        return query;
      }

      @Override
      public boolean available(String recipeId, MachineRecipe definition) {
        var selectors = definition == null ? catalog.selectors(recipeId) : definition.selectors();
        var requiredInputs = definition == null ? catalog.fluidInputRequirements(recipeId) : definition
            .fluidInputRequirements();
        var requiredOutputs = requiredOutputs(recipeId, definition);

        return fluids.inputsAvailable(requiredInputs, selectors.fluidInput())
            && fluids.outputsAvailable(requiredOutputs, selectors.fluidOutput());
      }

      @Override
      public RecipeResourcePlanning outputs(String recipeId, MachineRecipe definition) {
        if (layout == null) {
          layout = new FluidOutputLayout(ticket, ticket.level().registryAccess());
        }
        var planning = layout.planRequirements(
            requiredOutputs(recipeId, definition),
            definition == null ? catalog.selectors(recipeId).fluidOutput() : definition.selectors().fluidOutput());

        return new ResourcePlanningAdapter<>(planning::status, planning::advance);
      }
    };
  }

  private List<FluidRequirement> requiredOutputs(String recipeId, MachineRecipe definition) {
    return definition == null ? catalog.guaranteedFluidOutputs(recipeId) : definition
        .guaranteedFluidOutputRequirements();
  }
}
