package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.recipe.MachineRecipeDeclaration;
import java.util.ArrayList;
import java.util.List;
import com.lw.modularmachine.api.recipe.RecipeFailureAction;

/**
 * Private immutable declaration source; only effective recipes cross
 * persistence/network boundaries.
 */
final class RecipeModifierTemplate {
  private final MachineRecipeDeclaration source;
  private final List<RecipeModifier> machineRules;
  private final RecipeFailureAction machineFailureAction;

  RecipeModifierTemplate(MachineRecipeDeclaration source, List<RecipeModifier> machineRules, RecipeFailureAction machineFailureAction) {
    this.source = source;
    this.machineRules = List.copyOf(machineRules);
    this.machineFailureAction = machineFailureAction;
  }

  MachineRecipe resolve(List<RecipeModifier> upgradeRules, boolean durationMultiplier, MachineRecipeCompiler.Defaults defaults) {
    var inherited = new ArrayList<>(machineRules);
    inherited.addAll(upgradeRules);
    var effectiveDefaults = machineFailureAction == null ? defaults : new MachineRecipeCompiler.Defaults(
        defaults.parallelized(),
        machineFailureAction);
    return MachineRecipeCompiler.compile(source, inherited, durationMultiplier, effectiveDefaults);
  }
}
