package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import com.lw.modularmachine.api.recipe.RecipeSelectors;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.recipe.search.index.RequiredInputQuery;
import com.lw.modularmachine.machine.recipe.search.index.ResourceInputQuery;
import com.lw.modularmachine.machine.recipe.search.index.fluid.FluidRecipeIndex;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.api.recipe.FluidRequirement;

/**
 * Fluid identity coarse filter, component/count checks and exact output layout.
 */
@ApiStatus.Internal
final class FluidRecipeSearchResource implements RecipeSearchResource {
  private final RecipeCatalog catalog;
  private final FluidRecipeIndex index;
  private final Map<String, List<FluidStack>> inputs;
  private final Map<String, List<FluidStack>> outputs;

  FluidRecipeSearchResource(RecipeCatalog catalog) {
    this.catalog = catalog;
    var ids = catalog.recipes().candidates().stream().map(RecipeCandidate::id).toList();
    var capturedInputs = new HashMap<String, List<FluidStack>>();
    var capturedOutputs = new HashMap<String, List<FluidStack>>();
    var selectors = new HashMap<String, RecipeSelectors>();
    for (var id : ids) {
      capturedInputs.put(id, catalog.fluidInputs(id));
      capturedOutputs.put(id, catalog.fluidOutputs(id));
      selectors.put(id, catalog.selectors(id));
    }
    inputs = Map.copyOf(capturedInputs);
    outputs = Map.copyOf(capturedOutputs);
    index = new FluidRecipeIndex(ids, inputs, selectors);
  }

  @Override
  public Captured capture(RecipeExecutionTicket ticket, RecipeSearchResources resources) {
    var fluids = resources.fluids(ticket);
    var query = new RequiredInputQuery<>(index.inputs(), fluids.inputKeys());

    return new Captured() {
      private FluidOutputLayout layout;

      @Override
      public ResourceInputQuery inputs() {
        return query;
      }

      @Override
      public boolean available(String recipeId, MachineRecipe definition) {
        var selectors = definition == null ? catalog.selectors(recipeId) : definition.selectors();
        var requiredInputs = definition == null ? inputs.getOrDefault(recipeId, List.of()) : definition
            .fluidInputRequirements().stream().map(FluidRequirement::stack).toList();
        var requiredOutputs = definition == null ? outputs.getOrDefault(recipeId, List.of()) : definition
            .fluidOutputRequirements().stream().filter(row -> row.chance() == 1 && !row.ignoreOutputCheck())
            .map(FluidRequirement::stack).toList();

        return fluids.inputsAvailable(requiredInputs, selectors.fluidInput())
            && fluids.outputsAvailable(requiredOutputs, selectors.fluidOutput());
      }

      @Override
      public RecipeResourcePlanning outputs(String recipeId, MachineRecipe definition) {
        if (layout == null) {
          layout = new FluidOutputLayout(ticket, ticket.level().registryAccess());
        }
        var required = definition == null ? outputs.getOrDefault(recipeId, List.of()) : definition
            .fluidOutputRequirements().stream().filter(row -> row.chance() == 1 && !row.ignoreOutputCheck())
            .map(FluidRequirement::stack).toList();
        var planning = layout.plan(
            required,
            definition == null ? catalog.selectors(recipeId).fluidOutput() : definition.selectors().fluidOutput());

        return new ResourcePlanningAdapter<>(planning::status, planning::advance);
      }
    };
  }
}
