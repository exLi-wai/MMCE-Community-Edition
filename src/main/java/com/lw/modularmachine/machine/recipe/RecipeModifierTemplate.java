package com.lw.modularmachine.machine.recipe;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.HolderLookup;

/**
 * Private immutable declaration source; only effective recipes cross
 * persistence/network boundaries.
 */
final class RecipeModifierTemplate {
  private final JsonObject source;
  private final List<RecipeModifier> machineRules;

  RecipeModifierTemplate(JsonObject source, List<RecipeModifier> machineRules) {
    this.source = source.deepCopy();
    this.machineRules = List.copyOf(machineRules);
  }

  MachineRecipe resolve(List<RecipeModifier> upgradeRules, HolderLookup.Provider registries) {
    var inherited = new ArrayList<>(machineRules);
    inherited.addAll(upgradeRules);
    var effective = MachineRecipeJson.normalize(source, inherited);

    return MachineRecipe.CODEC.codec().parse(registries.createSerializationContext(JsonOps.INSTANCE), effective)
        .getOrThrow();
  }
}
