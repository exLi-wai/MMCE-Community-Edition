package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.api.recipe.IngredientArrayData;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.recipe.ItemResourceKey;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.recipe.RecipeIngredientArrayResolution.Selection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Captures real component prototypes once, under the same search work budget.
 */
@ApiStatus.Internal
final class RecipeIngredientItemExpansion {
  private record ItemSelector(Item item, String selector) {
  }

  private record PhysicalSlot(Object storage, int slot) {
  }

  private final RecipeExecutionTicket ticket;
  private final List<IngredientArrayData.Group> declarations;
  private final Map<ItemSelector, LinkedHashMap<ItemResourceKey, ItemStack>> inventory = new HashMap<>();
  private final HashSet<PhysicalSlot> captured = new HashSet<>();
  private final List<List<Selection>> groups = new ArrayList<>();
  private boolean inventoryReady;
  private boolean complete;
  private int portIndex, slotIndex, groupIndex, choiceIndex, prototypeIndex;
  private List<ItemStack> prototypes;
  private List<ItemStack> currentStacks;

  RecipeIngredientItemExpansion(RecipeExecutionTicket ticket, MachineRecipe source) {
    this.ticket = ticket;
    declarations = source.ingredientArrays().inputs();
    inventoryReady = declarations.stream().flatMap(value -> value.alternatives().stream())
        .noneMatch(value -> value.match().equals("item"));
    declarations.forEach(ignored -> groups.add(new ArrayList<>()));
  }

  int advance(int budget) {
    int workUsed = 0;
    while (!complete && workUsed < budget) {
      workUsed++;
      if (!inventoryReady) {
        captureSlot();
      } else {
        expandChoice();
      }
    }

    return workUsed;
  }

  boolean complete() {
    return complete;
  }

  int alternativeCount(int index) {
    return groups.get(index).size();
  }

  List<Selection> selected(int[] indices) {
    var selected = new ArrayList<Selection>(indices.length);
    for (int index = 0; index < indices.length; index++) {
      selected.add(indices[index] == -1 ? new Selection(-1, Optional.empty()) : groups.get(index).get(indices[index]));
    }

    return List.copyOf(selected);
  }

  private void captureSlot() {
    if (portIndex == ticket.ports().size()) {
      inventoryReady = true;
      return;
    }
    var current = ticket.ports().get(portIndex);
    if (current.kind() != PortKind.ITEM_INPUT) {
      portIndex++;
      return;
    }
    if (currentStacks == null) {
      currentStacks = current.items();
    }
    if (slotIndex == currentStacks.size()) {
      portIndex++;
      slotIndex = 0;
      currentStacks = null;
      return;
    }
    int index = slotIndex++;
    if (!captured.add(new PhysicalSlot(current.physicalIdentity(), index))) {
      return;
    }
    var stack = currentStacks.get(index);
    if (stack.isEmpty()) {
      return;
    }
    var prototype = stack.copyWithCount(1);
    inventory.computeIfAbsent(new ItemSelector(stack.getItem(), current.selector()), ignored -> new LinkedHashMap<>())
        .putIfAbsent(ItemResourceKey.of(prototype, ticket.level().registryAccess()), prototype);
  }

  private void expandChoice() {
    if (groupIndex == groups.size()) {
      complete = true;
      return;
    }
    var declaration = declarations.get(groupIndex);
    if (choiceIndex == declaration.alternatives().size()) {
      groupIndex++;
      choiceIndex = 0;
      return;
    }
    var candidate = declaration.alternatives().get(choiceIndex);
    if (!candidate.match().equals("item")) {
      groups.get(groupIndex).add(new Selection(choiceIndex++, Optional.empty()));
      return;
    }
    if (prototypes == null) {
      var found = inventory.get(new ItemSelector(candidate.item().orElseThrow().getItem(), declaration.selector()));
      prototypes = found == null ? List.of() : List.copyOf(found.values());
      prototypeIndex = 0;
    }
    if (prototypes.isEmpty()) {
      groups.get(groupIndex).add(new Selection(choiceIndex++, Optional.empty()));
      prototypes = null;
      return;
    }
    groups.get(groupIndex).add(new Selection(choiceIndex, Optional.of(prototypes.get(prototypeIndex++))));
    if (prototypeIndex == prototypes.size()) {
      choiceIndex++;
      prototypes = null;
    }
  }
}
