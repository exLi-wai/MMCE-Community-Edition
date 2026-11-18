package com.lw.modularmachine.api.recipe;

import com.lw.modularmachine.util.math.ExactIntegers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.PrimitiveCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;

/**
 * Chunk resource claims. Input leaves at least limit; output fills at most
 * limit.
 */
public record WorldPoolRecipeData(Optional<Claim> input, Optional<Claim> output) {
  public static final WorldPoolRecipeData NONE = new WorldPoolRecipeData(Optional.empty(), Optional.empty());

  public static MapCodec<WorldPoolRecipeData> codec(String field, boolean hasInput) {
    var outputCodec = Claim.CODEC.optionalFieldOf(field + "_output");
    if (!hasInput) {
      return outputCodec.xmap(output -> new WorldPoolRecipeData(Optional.empty(), output), WorldPoolRecipeData::output);
    }
    return RecordCodecBuilder.mapCodec(
        instance -> instance.group(
            Claim.CODEC.optionalFieldOf(field + "_input").forGetter(WorldPoolRecipeData::input),
            outputCodec.forGetter(WorldPoolRecipeData::output)).apply(instance, WorldPoolRecipeData::new));
  }

  public boolean present() {
    return input.isPresent() || output.isPresent();
  }

  public WorldPoolRecipeData scaled(int instances) {
    return new WorldPoolRecipeData(
        input.map(claim -> claim.scaled(instances)),
        output.map(claim -> claim.scaled(instances)));
  }

  /**
   * Radius is Chebyshev distance in chunks. Multi-chunk radius zero remains
   * multi-chunk.
   */
  public record Claim(String kind, long amount, boolean multichunk, int radius, long limit) {
    public static final int MAX_RADIUS = 4;
    private static final Codec<Long> LIMIT = new PrimitiveCodec<>() {
      @Override
      public <T> DataResult<Long> read(DynamicOps<T> ops, T input) {
        return ops.getNumberValue(input).flatMap(number -> {
          try {
            long value = ExactIntegers.longValueExact(number);
            return value >= 0 ? DataResult.success(value) : DataResult.error(() -> "Negative chunk resource limit");
          } catch (ArithmeticException | NumberFormatException invalid) {
            return DataResult.error(() -> "Expected an exact chunk resource limit");
          }
        });
      }

      @Override
      public <T> T write(DynamicOps<T> ops, Long value) {
        return ops.createLong(value);
      }
    };
    public static final Codec<Claim> CODEC = RecordCodecBuilder.create(
        instance -> instance.group(
            Codec.STRING.optionalFieldOf("type", "").forGetter(Claim::kind),
            BufferedAmountRecipeData.AMOUNT.fieldOf("amount").forGetter(Claim::amount),
            Codec.BOOL.optionalFieldOf("multichunk", false).forGetter(Claim::multichunk),
            Codec.intRange(0, MAX_RADIUS).optionalFieldOf("radius", 0).forGetter(Claim::radius),
            LIMIT.optionalFieldOf("limit", 0L).forGetter(Claim::limit)).apply(instance, Claim::new));

    public Claim {
      if (kind == null || kind.length() > 128 || amount <= 0 || limit < 0 || radius < 0 || radius > MAX_RADIUS) {
        throw new IllegalArgumentException("Invalid chunk resource claim");
      }
    }

    private Claim scaled(int instances) {
      return new Claim(kind, Math.multiplyExact(amount, instances), multichunk, radius, limit);
    }
  }
}
