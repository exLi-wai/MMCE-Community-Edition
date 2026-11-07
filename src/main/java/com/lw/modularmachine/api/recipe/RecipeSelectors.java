package com.lw.modularmachine.api.recipe;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.lw.modularmachine.machine.recipe.ResourceSelector;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Set;

/**
 * Fluid/energy phases select one disjoint port cohort; item requirements carry
 * their own selector.
 */
public record RecipeSelectors(String fluidInput, String fluidOutput, String energyInput, String fluidPerTick) {
  public static final RecipeSelectors NONE = new RecipeSelectors("", "", "", "");
  public static final Codec<RecipeSelectors> CODEC = RecordCodecBuilder.create(
      instance -> instance
          .group(
              Codec.STRING.optionalFieldOf("fluid_input", "").forGetter(RecipeSelectors::fluidInput),
              Codec.STRING.optionalFieldOf("fluid_output", "").forGetter(RecipeSelectors::fluidOutput),
              Codec.STRING.optionalFieldOf("energy_input", "").forGetter(RecipeSelectors::energyInput),
              Codec.STRING.optionalFieldOf("fluid_per_tick", "").forGetter(RecipeSelectors::fluidPerTick))
          .apply(instance, RecipeSelectors::new));

  public RecipeSelectors {
    ResourceSelector.require(fluidInput);
    ResourceSelector.require(fluidOutput);
    ResourceSelector.require(energyInput);
    ResourceSelector.require(fluidPerTick);
  }

  public static RecipeSelectors parse(JsonElement source, String path) {
    if (!source.isJsonObject()
        || !Set.of("fluid_input", "fluid_output", "energy_input", "fluid_per_tick")
            .containsAll(source.getAsJsonObject().keySet())) {
      throw new JsonParseException(path + ": Invalid phase selector fields");
    }
    for (var entry : source.getAsJsonObject().entrySet()) {
      if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
        throw new JsonParseException(path + "." + entry.getKey() + ": Expected selector string");
      }
    }

    return CODEC.parse(JsonOps.INSTANCE, source).getOrThrow();
  }
}
