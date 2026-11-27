package com.lw.modularmachine.api.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import java.util.Objects;
import java.util.stream.Stream;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.neoforged.neoforge.fluids.FluidStack;
import com.lw.modularmachine.machine.recipe.ResourceSelector;

/**
 * A component-preserving fluid prototype, quantity and per-instance
 * probability.
 */
public final class FluidRequirement {
  public static final MapCodec<FluidRequirement> MAP_CODEC = new MapCodec<>() {
    @Override
    public <T> Stream<T> keys(DynamicOps<T> ops) {
      return Stream.of("id", "amount", "components", "chance", "parallel_unaffected", "selector", "ignore_output_check")
          .map(ops::createString);
    }

    @Override
    public <T> DataResult<FluidRequirement> decode(DynamicOps<T> ops, MapLike<T> input) {
      return FluidStack.CODEC.parse(ops, ops.createMap(input.entries())).flatMap(
          stack -> Codec.DOUBLE.optionalFieldOf("chance", 1.0D).decode(ops, input).flatMap(
              chance -> Codec.BOOL.optionalFieldOf("parallel_unaffected", false).decode(ops, input).flatMap(
                  fixed -> Codec.BOOL.optionalFieldOf("ignore_output_check", false).decode(ops, input).flatMap(
                      ignored -> Codec.STRING.optionalFieldOf("selector").decode(ops, input).map(
                          selector -> selector.map(value -> new FluidRequirement(stack, chance, fixed, value))
                              .orElseGet(() -> new FluidRequirement(stack, chance, fixed))
                              .withIgnoreOutputCheck(ignored).capture(ops))))));
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
      if (value.ignoreOutputCheck) {
        prefix.add("ignore_output_check", Codec.BOOL.encodeStart(ops, true));
      }
      if (value.selectorSpecified) {
        prefix.add("selector", Codec.STRING.encodeStart(ops, value.selector));
      }
      return prefix;
    }
  };
  public static final Codec<FluidRequirement> CODEC = MAP_CODEC.codec();
  private record Components(DynamicOps<Tag> ops, Tag encoded) {
    private FluidStack copy(FluidStack prototype) {
      var patch = DataComponentPatch.CODEC.parse(ops, encoded.copy()).getOrThrow();
      return new FluidStack(prototype.getFluidHolder(), prototype.getAmount(), patch);
    }
  }

  private final FluidStack stack;
  private final Components components;
  private final double chance;
  private final boolean parallelUnaffected;
  private final String selector;
  private final boolean selectorSpecified;
  private final boolean ignoreOutputCheck;

  public FluidRequirement(FluidStack stack, double chance) {
    this(stack, chance, false);
  }

  public FluidRequirement(FluidStack stack, double chance, boolean parallelUnaffected) {
    this(stack, chance, parallelUnaffected, "", false, false, null);
  }

  public FluidRequirement(FluidStack stack, double chance, boolean parallelUnaffected, String selector) {
    this(stack, chance, parallelUnaffected, selector, true, false, null);
  }

  private FluidRequirement(FluidStack stack, double chance, boolean parallelUnaffected, String selector, boolean selectorSpecified, boolean ignoreOutputCheck, Components components) {
    Objects.requireNonNull(stack, "stack");
    if (stack.isEmpty() || !Double.isFinite(chance) || chance < 0 || chance > 1) {
      throw new IllegalArgumentException("Fluid requirements need a positive quantity and probability within 0..1");
    }
    this.stack = stack.copy();
    this.components = components;
    this.chance = chance;
    this.parallelUnaffected = parallelUnaffected;
    this.selector = ResourceSelector.require(selector);
    this.selectorSpecified = selectorSpecified;
    this.ignoreOutputCheck = ignoreOutputCheck;
  }

  public FluidRequirement capture(HolderLookup.Provider registries) {
    return needsCapture() ? capture(registries.createSerializationContext(NbtOps.INSTANCE)) : this;
  }

  public FluidRequirement capture(DynamicOps<?> sourceOps) {
    if (!needsCapture()) {
      return this;
    }
    DynamicOps<Tag> ops = sourceOps instanceof RegistryOps<?> registryOps ? registryOps
        .withParent(NbtOps.INSTANCE) : NbtOps.INSTANCE;
    var encoded = DataComponentPatch.CODEC.encodeStart(ops, stack.getComponentsPatch()).getOrThrow();
    var captured = new Components(ops, encoded.copy());
    var detached = captured.copy(stack);
    if (!FluidStack.matches(stack, detached)) {
      throw new IllegalArgumentException("Lossy fluid components cannot be used in a recipe requirement");
    }
    return new FluidRequirement(
        detached,
        chance,
        parallelUnaffected,
        selector,
        selectorSpecified,
        ignoreOutputCheck,
        captured);
  }

  private boolean needsCapture() {
    return components == null && !stack.isComponentsPatchEmpty();
  }

  public FluidStack stack() {
    return components == null ? stack.copy() : components.copy(stack);
  }

  int amount() {
    return stack.getAmount();
  }

  public double chance() {
    return chance;
  }

  public boolean parallelUnaffected() {
    return parallelUnaffected;
  }

  public boolean ignoreOutputCheck() {
    return ignoreOutputCheck;
  }

  public FluidRequirement withIgnoreOutputCheck(boolean value) {
    return new FluidRequirement(stack, chance, parallelUnaffected, selector, selectorSpecified, value, components);
  }

  public FluidRequirement withAmountAndChance(int amount, double probability) {
    return new FluidRequirement(
        stack.copyWithAmount(amount),
        probability,
        parallelUnaffected,
        selector,
        selectorSpecified,
        ignoreOutputCheck,
        components);
  }

  public boolean selectorSpecified() {
    return selectorSpecified;
  }

  public String selector() {
    return selector;
  }

  /**
   * Only an omitted selector inherits the phase default; explicit empty selects
   * unmarked storage.
   */
  public String selector(String phaseDefault) {
    return selectorSpecified ? selector : ResourceSelector.require(phaseDefault);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof FluidRequirement value
        && chance == value.chance
        && parallelUnaffected == value.parallelUnaffected
        && ignoreOutputCheck == value.ignoreOutputCheck
        && selectorSpecified == value.selectorSpecified
        && selector.equals(value.selector)
        && FluidStack.matches(stack, value.stack);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        stack.getFluid(),
        stack.getComponents(),
        stack.getAmount(),
        chance,
        parallelUnaffected,
        ignoreOutputCheck,
        selector,
        selectorSpecified);
  }
}
