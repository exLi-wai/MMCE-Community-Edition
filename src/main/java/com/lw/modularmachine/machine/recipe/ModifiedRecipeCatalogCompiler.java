package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.recipe.RecipeSelectors;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Bounded preparation per rule signature; never falls back to unmodified
 * recipes on failure.
 */
final class ModifiedRecipeCatalogCompiler {
  static final int RECIPES_PER_TICK = 8;
  private final ItemRecipeCatalog base;
  private final MachineDefinition machine;
  private final Map<ResourceLocation, RecipeModifierTemplate> templates;
  private final List<RecipeModifier> rules;
  private final List<String> ids;
  private final List<RecipeCandidate<ItemResourceKey>> candidates = new ArrayList<>();
  private final Map<String, MachineRecipe> definitions = new HashMap<>();
  private int cursor;
  private long lastTick = Long.MIN_VALUE;
  private boolean failed;
  private ItemRecipeCatalog result;

  ModifiedRecipeCatalogCompiler(ItemRecipeCatalog base, MachineDefinition machine, Map<ResourceLocation, RecipeModifierTemplate> templates, List<RecipeModifier> rules) {
    this.base = base;
    this.machine = machine;
    this.templates = templates;
    this.rules = List.copyOf(rules);
    ids = base.recipes().candidates().stream().map(RecipeCandidate::id).toList();
  }

  void advance(long tick, HolderLookup.Provider registries) {
    if (failed || result != null || lastTick == tick || !base.isCurrent()) {
      return;
    }
    lastTick = tick;
    try {
      int used = 0;
      while (cursor < ids.size() && used < RECIPES_PER_TICK) {
        var id = ResourceLocation.parse(ids.get(cursor));
        var template = templates.get(id);
        if (template == null) {
          throw new IllegalArgumentException("Missing original modifier template: " + id);
        }
        var recipe = template.resolve(rules, registries);
        MachineRecipeService.validatePorts(id, recipe, machine);
        candidates.add(recipe.compile(id, registries));
        definitions.put(id.toString(), recipe);
        cursor++;
        used++;
      }
      if (cursor == ids.size()) {
        var inputs = new HashMap<String, List<FluidStack>>();
        var outputs = new HashMap<String, List<FluidStack>>();
        var selectors = new HashMap<String, RecipeSelectors>();
        definitions.forEach((id, recipe) -> {
          inputs.put(id, recipe.fluidInputs());
          outputs.put(id, recipe.fluidOutputs());
          selectors.put(id, recipe.selectors());
        });
        result = new ItemRecipeCatalog(
            base.machineId(),
            new CompiledRecipeSet<>(candidates),
            inputs,
            outputs,
            base::isCurrent,
            base,
            definitions,
            rules,
            selectors);
        candidates.clear();
        definitions.clear();
      }
    } catch (RuntimeException invalid) {
      failed = true;
      candidates.clear();
      definitions.clear();
      ModularMachine.LOGGER
          .warn("Context recipe modifiers failed for machine {} at candidate {}", base.machineId(), cursor, invalid);
    }
  }

  ItemRecipeCatalog result() {
    return result;
  }

  boolean failed() {
    return failed;
  }
}
