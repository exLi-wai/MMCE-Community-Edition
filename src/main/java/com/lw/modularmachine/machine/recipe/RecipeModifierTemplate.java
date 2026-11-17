package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.HolderLookup;
import com.lw.modularmachine.api.recipe.RecipeFailureAction;

/**
 * Private immutable declaration source; only effective recipes cross
 * persistence/network boundaries.
 */
final class RecipeModifierTemplate {
  private final JsonObject source;
  private final List<RecipeModifier> machineRules;
  private final RecipeFailureAction machineFailureAction;

  RecipeModifierTemplate(JsonObject source, List<RecipeModifier> machineRules) {
    this(source, machineRules, null);
  }

  RecipeModifierTemplate(JsonObject source, List<RecipeModifier> machineRules, RecipeFailureAction machineFailureAction) {
    this.source = source.deepCopy();
    this.machineRules = List.copyOf(machineRules);
    this.machineFailureAction = machineFailureAction;
  }

  MachineRecipe resolve(List<RecipeModifier> upgradeRules, HolderLookup.Provider registries) {
    return resolve(upgradeRules, registries, false);
  }

  MachineRecipe resolve(List<RecipeModifier> upgradeRules, HolderLookup.Provider registries, boolean durationMultiplier) {
    return resolve(upgradeRules, registries, durationMultiplier, MachineRecipeJson.currentDefaults());
  }

  MachineRecipe resolve(List<RecipeModifier> upgradeRules, HolderLookup.Provider registries, boolean durationMultiplier, MachineRecipeJson.Defaults defaults) {
    var inherited = new ArrayList<>(machineRules);
    inherited.addAll(upgradeRules);
    var effectiveDefaults = machineFailureAction == null ? defaults : new MachineRecipeJson.Defaults(
        defaults.parallelized(),
        machineFailureAction);
    var effective = MachineRecipeJson.normalize(source, inherited, durationMultiplier, effectiveDefaults);

    return MachineRecipe.CODEC.codec().parse(registries.createSerializationContext(JsonOps.INSTANCE), effective)
        .getOrThrow();
  }
}
