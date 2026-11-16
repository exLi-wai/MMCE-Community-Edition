package com.lw.modularmachine.machine.recipe.resource;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import org.jetbrains.annotations.ApiStatus;

/** Long FE/t requirements; transfer limits remain in the real energy port. */
@ApiStatus.Internal
public record EnergyRecipeData(long inputPerTick, long outputPerTick) {
  public static final EnergyRecipeData NONE = new EnergyRecipeData(0, 0);
  public static final MapCodec<EnergyRecipeData> CODEC = RecordCodecBuilder.mapCodec(
      instance -> instance
          .group(
              Codec.LONG.optionalFieldOf("energy_per_tick", 0L).forGetter(EnergyRecipeData::inputPerTick),
              Codec.LONG.optionalFieldOf("energy_output_per_tick", 0L).forGetter(EnergyRecipeData::outputPerTick))
          .apply(instance, EnergyRecipeData::new));

  public EnergyRecipeData {
    if (inputPerTick < 0 || outputPerTick < 0) {
      throw new IllegalArgumentException("Energy per tick cannot be negative");
    }
  }
}
