package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.controller.ControllerOwnership;
import com.lw.modularmachine.api.controller.ControllerOwnershipApi;
import com.lw.modularmachine.api.recipe.RecipeItemCallbacks;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.recipe.RecipeItemCallbackService.InputCheck;
import com.lw.modularmachine.machine.recipe.search.RecipeCandidate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/** Internal full-demand input inspection before any physical resource lease. */
@ApiStatus.Internal
public final class RecipeItemPreparation {
  public static boolean transformsOutputs(MachineRecipe recipe) {
    return recipe != null
        && recipe.scriptOptions().itemCallbacks().stream()
            .anyMatch(binding -> binding.io() == RecipeModifier.Io.OUTPUT && !binding.modifiers().isEmpty());
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

  private record PhysicalSlot(Object storage, int slot) {
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

  static InputCheck checkInputs(MachineRecipe recipe, RecipeExecutionTicket ticket, Optional<String> thread, int instances, FrozenItemRun run) {
    var slices = new ArrayList<RecipeItemCallbackService.InputItem>();
    var reserved = new HashMap<PhysicalSlot, Long>();
    for (int row = 0; row < recipe.inputs().size(); row++) {
      var input = recipe.inputs().get(row);
      long remaining = input.parallelUnaffected() && run.parallelInstances() > 1 ? input.amount() : Math
          .multiplyExact(input.amount(), instances);
      var key = ItemResourceKey.of(input.prototype(ticket.level().registryAccess()), ticket.level().registryAccess())
          .withSelector(input.selector());
      if (input.ignoresComponents()) {
        key = key.ignoringComponents();
      }
      for (var port : ticket.ports()) {
        if (port.kind() != PortKind.ITEM_INPUT || !port.selector().equals(input.selector())) {
          continue;
        }
        var stacks = port.items();
        for (int slot = 0; slot < stacks.size() && remaining > 0; slot++) {
          var actual = stacks.get(slot);
          if (!key.matches(actual)) {
            continue;
          }
          var physical = new PhysicalSlot(port.physicalIdentity(), slot);
          long used = reserved.getOrDefault(physical, 0L);
          long amount = Math.min(remaining, Math.max(0, port.itemAmount(slot) - used));
          if (amount > 0) {
            reserved.put(physical, Math.addExact(used, amount));
            slices.add(new RecipeItemCallbackService.InputItem(row, actual, amount));
            remaining -= amount;
          }
        }
        if (remaining == 0) {
          break;
        }
      }
      if (remaining > 0) {
        return InputCheck.STALE;
      }
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
