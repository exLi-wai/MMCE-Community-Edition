package com.lw.modularmachine.api.recipe;

import com.lw.modularmachine.util.math.ExactIntegers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.PrimitiveCodec;

/** Mekanism laser energy consumed once when starting each recipe instance. */
public record LaserRecipeData(long input) {
  public static final LaserRecipeData NONE = new LaserRecipeData(0);
  private static final Codec<Long> POSITIVE_LONG = new PrimitiveCodec<Long>() {
    @Override
    public <T> DataResult<Long> read(DynamicOps<T> ops, T input) {
      return ops.getNumberValue(input).flatMap(number -> {
        try {
          return positive(ExactIntegers.longValueExact(number));
        } catch (ArithmeticException | NumberFormatException invalid) {
          return DataResult.error(() -> "Laser input requires an exact long");
        }
      });
    }

    @Override
    public <T> T write(DynamicOps<T> ops, Long value) {
      return ops.createLong(value);
    }
  }.validate(LaserRecipeData::positive);
  public static final MapCodec<LaserRecipeData> CODEC = POSITIVE_LONG.optionalFieldOf("laser_input", 0L)
      .xmap(LaserRecipeData::new, LaserRecipeData::input);

  private static DataResult<Long> positive(long value) {
    return value > 0 ? DataResult.success(value) : DataResult.error(() -> "Laser input must be positive");
  }

  public LaserRecipeData {
    if (input < 0) {
      throw new IllegalArgumentException("Laser input cannot be negative");
    }
  }
}
