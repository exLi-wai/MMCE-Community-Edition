package com.lw.modularmachine.api.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import java.util.Objects;
import java.util.stream.Stream;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * A component-preserving fluid prototype, quantity and per-instance
 * probability.
 */
public final class FluidRequirement {
  public static final MapCodec<FluidRequirement> MAP_CODEC = new MapCodec<>() {
    @Override
    public <T> Stream<T> keys(DynamicOps<T> ops) {
      return Stream.of("id", "amount", "components", "chance", "parallel_unaffected").map(ops::createString);
    }

    @Override
    public <T> DataResult<FluidRequirement> decode(DynamicOps<T> ops, MapLike<T> input) {
      return FluidStack.CODEC.parse(ops, ops.createMap(input.entries())).flatMap(
          stack -> Codec.DOUBLE.optionalFieldOf("chance", 1.0D).decode(ops, input).flatMap(
              chance -> Codec.BOOL.optionalFieldOf("parallel_unaffected", false).decode(ops, input)
                  .map(fixed -> new FluidRequirement(stack, chance, fixed))));
    }

    @Override
    public <T> RecordBuilder<T> encode(FluidRequirement value, DynamicOps<T> ops, RecordBuilder<T> prefix) {
      var encoded = FluidStack.CODEC.encodeStart(ops, value.stack);
      prefix.withErrorsFrom(encoded);
      encoded.result().ifPresent(
          json -> ops.getMapValues(json).result()
              .ifPresent(entries -> entries.forEach(entry -> prefix.add(entry.getFirst(), entry.getSecond()))));
      if (value.chance != 1) {
        prefix.add("chance", Codec.DOUBLE.encodeStart(ops, value.chance));
      }
      if (value.parallelUnaffected) {
        prefix.add("parallel_unaffected", Codec.BOOL.encodeStart(ops, true));
      }
      return prefix;
    }
  };
  public static final Codec<FluidRequirement> CODEC = MAP_CODEC.codec();
  private final FluidStack stack;
  private final double chance;
  private final boolean parallelUnaffected;

  public FluidRequirement(FluidStack stack, double chance) {
    this(stack, chance, false);
  }

  public FluidRequirement(FluidStack stack, double chance, boolean parallelUnaffected) {
    Objects.requireNonNull(stack, "stack");
    if (stack.isEmpty() || !Double.isFinite(chance) || chance < 0 || chance > 1) {
      throw new IllegalArgumentException("Fluid requirements need a positive quantity and probability within 0..1");
    }
    this.stack = stack.copy();
    this.chance = chance;
    this.parallelUnaffected = parallelUnaffected;
  }

  public FluidStack stack() {
    return stack.copy();
  }

  public double chance() {
    return chance;
  }

  public boolean parallelUnaffected() {
    return parallelUnaffected;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof FluidRequirement value
        && chance == value.chance
        && parallelUnaffected == value.parallelUnaffected
        && FluidStack.matches(stack, value.stack);
  }

  @Override
  public int hashCode() {
    return Objects.hash(stack.getFluid(), stack.getComponents(), stack.getAmount(), chance, parallelUnaffected);
  }
}
