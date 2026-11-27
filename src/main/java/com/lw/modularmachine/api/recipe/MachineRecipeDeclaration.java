package com.lw.modularmachine.api.recipe;

import com.lw.modularmachine.api.recipe.resource.RecipeResources;
import com.lw.modularmachine.api.smart.SmartNumberRequirement;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/**
 * Original recipe values before machine defaults, upgrades and structure
 * modifiers are applied.
 */
public record MachineRecipeDeclaration(
    ResourceLocation machineId,
    int priority,
    int duration,
    RecipeResources resources,
    Optional<RecipeFailureAction> failureAction,
    Optional<Boolean> parallelized,
    long randomSeed,
    List<SmartNumberRequirement> interfaceInputs,
    RecipeSelectors selectors,
    RecipeExecutionOptions executionOptions,
    RecipeScriptOptions scriptOptions,
    IngredientArrayData ingredientArrays,
    TriggeredResourceData triggeredResources,
    List<RecipeModifier> modifiers,
    boolean legacyFluidPerTick,
    Set<String> declaredSelectorFields) {
  public MachineRecipeDeclaration {
    Objects.requireNonNull(machineId);
    Objects.requireNonNull(resources);
    Objects.requireNonNull(failureAction);
    Objects.requireNonNull(parallelized);
    Objects.requireNonNull(selectors);
    Objects.requireNonNull(executionOptions);
    Objects.requireNonNull(scriptOptions);
    Objects.requireNonNull(ingredientArrays);
    Objects.requireNonNull(triggeredResources);
    interfaceInputs = List.copyOf(interfaceInputs);
    modifiers = List.copyOf(modifiers);
    declaredSelectorFields = Set.copyOf(declaredSelectorFields);
    if (duration < 1 || randomSeed < 0) {
      throw new IllegalArgumentException("A machine recipe needs a positive duration and nonnegative seed");
    }
    if (!Set.of("fluid_input", "fluid_output", "energy_input", "fluid_per_tick", "energy_output")
        .containsAll(declaredSelectorFields) || !declaredSelectorFields.containsAll(nonemptySelectors(selectors))) {
      throw new IllegalArgumentException("Invalid declared phase selector fields");
    }
  }

  public MachineRecipeDeclaration(ResourceLocation machineId, int priority, int duration, RecipeResources resources, Optional<RecipeFailureAction> failureAction, Optional<Boolean> parallelized, long randomSeed, List<SmartNumberRequirement> interfaceInputs, RecipeSelectors selectors, RecipeExecutionOptions executionOptions, RecipeScriptOptions scriptOptions, IngredientArrayData ingredientArrays, TriggeredResourceData triggeredResources, List<RecipeModifier> modifiers, boolean legacyFluidPerTick) {
    this(machineId, priority, duration, resources, failureAction, parallelized, randomSeed, interfaceInputs, selectors,
        executionOptions, scriptOptions, ingredientArrays, triggeredResources, modifiers, legacyFluidPerTick,
        nonemptySelectors(selectors));
  }

  private static Set<String> nonemptySelectors(RecipeSelectors selectors) {
    var fields = new HashSet<String>();
    if (!selectors.fluidInput().isEmpty()) {
      fields.add("fluid_input");
    }
    if (!selectors.fluidOutput().isEmpty()) {
      fields.add("fluid_output");
    }
    if (!selectors.energyInput().isEmpty()) {
      fields.add("energy_input");
    }
    if (!selectors.fluidPerTick().isEmpty()) {
      fields.add("fluid_per_tick");
    }
    if (!selectors.energyOutput().isEmpty()) {
      fields.add("energy_output");
    }
    return fields;
  }
}
