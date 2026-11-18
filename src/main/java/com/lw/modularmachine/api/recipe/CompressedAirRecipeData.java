package com.lw.modularmachine.api.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;

/**
 * Positive air quantities at start and completion. Input quantities with the
 * same minimum pressure share a batch debit.
 */
public record CompressedAirRecipeData(Optional<Amount> input, Optional<Amount> output) {
  public static final CompressedAirRecipeData NONE = new CompressedAirRecipeData(Optional.empty(), Optional.empty());
  public static final MapCodec<CompressedAirRecipeData> CODEC = RecordCodecBuilder.mapCodec(
      instance -> instance
          .group(
              Amount.CODEC.optionalFieldOf("compressed_air_input").forGetter(CompressedAirRecipeData::input),
              Amount.CODEC.optionalFieldOf("compressed_air_output").forGetter(CompressedAirRecipeData::output))
          .apply(instance, CompressedAirRecipeData::new));

  public CompressedAirRecipeData scaled(int instances) {
    return new CompressedAirRecipeData(
        input.map(amount -> amount.scaled(instances)),
        output.map(amount -> amount.scaled(instances)));
  }

  public record Amount(float pressure, long air) {
    public static final Codec<Amount> CODEC = RecordCodecBuilder
        .create(
            instance -> instance
                .group(
                    Codec.floatRange(-1, 100).optionalFieldOf("pressure", 0.0F).forGetter(Amount::pressure),
                    BufferedAmountRecipeData.AMOUNT.fieldOf("air").forGetter(Amount::air))
                .apply(instance, Amount::new));

    public Amount {
      if (!Float.isFinite(pressure) || pressure < -1 || pressure > 100 || air < 1) {
        throw new IllegalArgumentException("Invalid compressed air requirement");
      }
    }

    private Amount scaled(int instances) {
      return new Amount(pressure, Math.multiplyExact(air, instances));
    }
  }
}
