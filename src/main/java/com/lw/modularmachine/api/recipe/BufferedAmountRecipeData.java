package com.lw.modularmachine.api.recipe;

import com.lw.modularmachine.util.math.ExactIntegers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.PrimitiveCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * One exact start debit and one completion credit for a scalar local resource.
 */
public record BufferedAmountRecipeData(long input, long output) {
  public static final BufferedAmountRecipeData NONE = new BufferedAmountRecipeData(0, 0);
  static final Codec<Long> AMOUNT = new PrimitiveCodec<>() {
    @Override
    public <T> DataResult<Long> read(DynamicOps<T> ops, T input) {
      return ops.getNumberValue(input).flatMap(number -> {
        try {
          long amount = ExactIntegers.longValueExact(number);
          return amount > 0 ? DataResult.success(amount) : DataResult.error(() -> "Amount must be positive");
        } catch (ArithmeticException | NumberFormatException invalid) {
          return DataResult.error(() -> "Expected an exact long amount");
        }
      });
    }

    @Override
    public <T> T write(DynamicOps<T> ops, Long value) {
      return ops.createLong(value);
    }
  };

  public BufferedAmountRecipeData {
    if (input < 0 || output < 0) {
      throw new IllegalArgumentException("Negative buffered resource amount");
    }
  }

  public static MapCodec<BufferedAmountRecipeData> codec(String inputField, String outputField) {
    return RecordCodecBuilder.mapCodec(
        instance -> instance
            .group(
                AMOUNT.optionalFieldOf(inputField, 0L).forGetter(BufferedAmountRecipeData::input),
                AMOUNT.optionalFieldOf(outputField, 0L).forGetter(BufferedAmountRecipeData::output))
            .apply(instance, BufferedAmountRecipeData::new));
  }

  public static MapCodec<BufferedAmountRecipeData> inputCodec(String field) {
    return AMOUNT.optionalFieldOf(field, 0L).flatXmap(
        amount -> DataResult.success(new BufferedAmountRecipeData(amount, 0)),
        data -> data.output() == 0 ? DataResult.success(data.input()) : DataResult
            .error(() -> "Outputs are unsupported"));
  }

  public BufferedAmountRecipeData scaled(int instances) {
    return new BufferedAmountRecipeData(Math.multiplyExact(input, instances), Math.multiplyExact(output, instances));
  }
}
