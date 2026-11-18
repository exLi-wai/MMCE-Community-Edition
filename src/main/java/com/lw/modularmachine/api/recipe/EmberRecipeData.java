package com.lw.modularmachine.api.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.PrimitiveCodec;
import java.math.BigDecimal;

/** Ember charged at start, in exact millionths of an Ember. */
public record EmberRecipeData(long micros) {
  private static final long MAX_MICROS = 100_000_000_000_000L;
  public static final EmberRecipeData NONE = new EmberRecipeData(0);
  private static final Codec<EmberRecipeData> AMOUNT = new PrimitiveCodec<>() {
    @Override
    public <T> DataResult<EmberRecipeData> read(DynamicOps<T> ops, T input) {
      return ops.getNumberValue(input).flatMap(number -> {
        try {
          return DataResult.success(of(number));
        } catch (IllegalArgumentException invalid) {
          return DataResult.error(invalid::getMessage);
        }
      });
    }

    @Override
    public <T> T write(DynamicOps<T> ops, EmberRecipeData value) {
      return ops.createDouble(value.amount());
    }
  };

  public static final MapCodec<EmberRecipeData> CODEC = AMOUNT.optionalFieldOf("ember_input", NONE);

  public EmberRecipeData {
    if (!supportsMicros(micros)) {
      throw new IllegalArgumentException("Ember input exceeds the supported range");
    }
  }

  public static boolean supportsMicros(long micros) {
    return micros >= 0 && micros <= MAX_MICROS;
  }

  public static EmberRecipeData of(Number number) {
    try {
      long micros = new BigDecimal(number.toString()).movePointRight(6).longValueExact();
      if (micros < 1) {
        throw new IllegalArgumentException("Ember input must be positive");
      }
      return new EmberRecipeData(micros);
    } catch (ArithmeticException | NumberFormatException invalid) {
      throw new IllegalArgumentException("Ember input requires at most six decimal places", invalid);
    }
  }

  public double amount() {
    return micros / 1_000_000.0;
  }
}
