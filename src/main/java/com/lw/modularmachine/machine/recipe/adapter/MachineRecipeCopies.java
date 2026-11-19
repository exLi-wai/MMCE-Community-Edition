package com.lw.modularmachine.machine.recipe.adapter;

import com.google.gson.JsonObject;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterImport;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal copies of original declarations; source machine modifiers are not
 * baked in.
 */
@ApiStatus.Internal
final class MachineRecipeCopies {
  private MachineRecipeCopies() {
  }

  static JsonObject copy(JsonObject original, ResourceLocation target, RecipeAdapterImport settings, HolderLookup.Provider registries) {
    var result = original.deepCopy();
    result.addProperty("machine", target.toString());
    if (settings.duration() > 0) {
      result.addProperty("duration", settings.duration());
    }
    if (settings.energyPerTick() > 0) {
      long originalEnergy = result.has("energy_per_tick") ? result.get("energy_per_tick").getAsBigDecimal()
          .longValueExact() : 0;
      result.addProperty("energy_per_tick", Math.addExact(originalEnergy, settings.energyPerTick()));
    }
    if (settings.prioritySpecified()) {
      result.addProperty("priority", settings.priority());
    }
    // Native conditions have already admitted the source in this generation.
    result.remove("neoforge:conditions");

    return RecipeAdapterCustomizations.apply(result, settings.customization(), registries);
  }
}
