package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.controller.ControllerOwnership;
import com.lw.modularmachine.api.controller.ControllerOwnershipApi;
import com.lw.modularmachine.api.recipe.RecipeItemCallbacks;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.machine.recipe.RecipeItemCallbackService.InputCheck;
import com.lw.modularmachine.machine.recipe.search.RecipeCandidate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/** Internal full-demand input inspection before any physical resource lease. */
@ApiStatus.Internal
public final class RecipeItemPreparation {
  public static boolean transformsOutputs(MachineRecipe recipe) {
    return recipe != null
        && (!recipe.ingredientArrays().randomOutputs().isEmpty()
            || recipe.scriptOptions().itemCallbacks().stream()
                .anyMatch(binding -> binding.io() == RecipeModifier.Io.OUTPUT && !binding.modifiers().isEmpty()));
  }

  /**
   * Original output prototypes must not reject an input candidate before its
   * modifier runs.
   */
  public static RecipeCandidate<ItemResourceKey> inputCandidate(RecipeCandidate<ItemResourceKey> candidate, MachineRecipe recipe) {
    return transformsOutputs(recipe) ? new RecipeCandidate<>(
        candidate.id(),
        candidate.priority(),
        candidate.inputs(),
        List.of(),
        candidate.interfaceInputs()) : candidate;
  }

  public static boolean hasInputChecker(MachineRecipe recipe) {
    return recipe != null
        && recipe.scriptOptions().itemCallbacks().stream()
            .anyMatch(binding -> binding.io() == RecipeModifier.Io.INPUT && binding.checker().isPresent());
  }

  private RecipeItemPreparation() {
  }

  public static RecipeItemCallbacks.Context context(RecipeExecutionTicket ticket, ResourceLocation recipe, Optional<String> thread, int instances) {
    var level = ticket.level();
    var owner = ticket.owner();
    var ownership = ControllerOwnershipApi.inspect(level, owner.structurePosition())
        .orElse(new ControllerOwnership(true, Optional.empty(), Optional.empty(), false));
    return new RecipeItemCallbacks.Context(
        level.dimension().location(),
        owner.structurePosition(),
        owner.structureMachineId(),
        recipe,
        thread.isPresent(),
        thread,
        level.getGameTime(),
        instances,
        RecipeModifier.Io.INPUT,
        0,
        1,
        ownership);
  }

  static InputCheck checkInputs(MachineRecipe recipe, RecipeExecutionTicket ticket, Optional<String> thread, int instances, FrozenRecipeRun run, RecipeItemInputAllocation allocation) {
    var slices = new ArrayList<RecipeItemCallbackService.InputItem>();
    if (allocation != null) {
      if (!allocation.matchesInstances(instances)) {
        return InputCheck.STALE;
      }
      allocation.appendCheckerSlices(slices);
    } else if (hasInputChecker(recipe)
        || !collectOrdinarySlices(recipe, ticket, instances, run.parallelInstances() > 1, slices)) {
      return InputCheck.STALE;
    }
    return checkCollectedInputs(recipe, ticket, thread, instances, run, slices);
  }

  static boolean collectOrdinarySlices(MachineRecipe recipe, RecipeExecutionTicket ticket, int instances, boolean aggregate, List<RecipeItemCallbackService.InputItem> slices) {
    var allocation = RecipeItemInputAllocation.capture(recipe, ticket, instances, aggregate);
    allocation.ifPresent(value -> value.appendCheckerSlices(slices));
    return allocation.isPresent();
  }

  private static InputCheck checkCollectedInputs(MachineRecipe recipe, RecipeExecutionTicket ticket, Optional<String> thread, int instances, FrozenRecipeRun run, List<RecipeItemCallbackService.InputItem> slices) {
    if (!recipe.itemPayments().tools().isEmpty()) {
      var tools = RecipeItemPayments.toolSlices(ticket, recipe, instances, run.parallelInstances() > 1);
      if (tools.isEmpty()) {
        return InputCheck.STALE;
      }
      slices.addAll(tools.orElseThrow());
    }
    if (recipe.scriptOptions().itemCallbacks().isEmpty()) {
      return InputCheck.ALLOWED;
    }
    var result = RecipeItemCallbackService.checkInputs(
        recipe.scriptOptions(),
        context(ticket, run.recipeId(), thread, instances),
        slices,
        ticket.level().registryAccess(),
        run.preparedItemCallbackEpoch());
    if (!run.preparedItemCallbacksCurrent() || !ticket.dependenciesCurrent()) {
      return InputCheck.STALE;
    }
    return result;
  }
}
