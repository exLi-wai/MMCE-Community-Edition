package com.lw.modularmachine.api.recipe;

import com.lw.modularmachine.machine.recipe.ItemStackSnapshots;
import com.lw.modularmachine.machine.recipe.ResourceSelector;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * One item requirement with its original quantity, matching and output options.
 */
public final class ItemAmount {
  public static final Codec<ItemAmount> CODEC = RecordCodecBuilder
      .create(
          instance -> instance
              .group(
                  ItemStack.SINGLE_ITEM_CODEC.fieldOf("stack").forGetter((ItemAmount value) -> value.prototype.stack()),
                  Codec.LONG.fieldOf("amount").forGetter(ItemAmount::amount),
                  Codec.DOUBLE.optionalFieldOf("chance", 1.0D).forGetter(ItemAmount::chance),
                  Codec.STRING.optionalFieldOf("selector", "").forGetter(ItemAmount::selector),
                  Codec.STRING.optionalFieldOf("match", "exact").forGetter(ItemAmount::match),
                  ItemStack.SINGLE_ITEM_CODEC.optionalFieldOf("preview_stack", ItemStack.EMPTY)
                      .forGetter(value -> value.preview.stack()),
                  Codec.BOOL.optionalFieldOf("parallel_unaffected", false).forGetter(ItemAmount::parallelUnaffected),
                  Codec.LONG.optionalFieldOf("min_amount")
                      .forGetter(value -> value.maxAmount == 0 ? Optional.empty() : Optional.of(value.minAmount)),
                  Codec.LONG.optionalFieldOf("max_amount")
                      .forGetter(value -> value.maxAmount == 0 ? Optional.empty() : Optional.of(value.maxAmount)),
                  Codec.BOOL.optionalFieldOf("ignore_output_check", false).forGetter(ItemAmount::ignoreOutputCheck),
                  RecipeStackPrototype.COMPONENT_OPS.forGetter(ignored -> NbtOps.INSTANCE))
              .apply(
                  instance,
                  (stack, amount, chance, selector, match, preview, fixed, min, max, ignored, ops) -> new ItemAmount(
                      stack,
                      amount,
                      chance,
                      selector,
                      match,
                      preview,
                      fixed,
                      min.orElse(0L),
                      max.orElse(0L),
                      ignored).capture(ops)));

  /**
   * Capture once with the declaration's registries and decode a private stack for
   * each component-bearing view.
   */
  record Prototype(ItemStack stack, DynamicOps<Tag> ops, Tag encoded) {
    private static final Prototype EMPTY = new Prototype(ItemStack.EMPTY, null, null);

    static Prototype of(ItemStack stack) {
      return stack.isEmpty() ? EMPTY : new Prototype(stack.copy(), null, null);
    }

    boolean needsCapture() {
      return encoded == null && !stack.isEmpty() && !stack.isComponentsPatchEmpty();
    }

    Prototype capture(DynamicOps<Tag> registryOps) {
      if (!needsCapture()) {
        return this;
      }
      var value = ItemStack.SINGLE_ITEM_CODEC.encodeStart(registryOps, stack).getOrThrow().copy();
      var detached = ItemStack.SINGLE_ITEM_CODEC.parse(registryOps, value.copy()).getOrThrow();
      if (!ItemStack.matches(stack, detached)) {
        throw new IllegalArgumentException("Lossy or nonpersistent recipe item components");
      }
      return new Prototype(detached, registryOps, value);
    }

    ItemStack copy() {
      return encoded == null ? stack.copy() : ItemStack.SINGLE_ITEM_CODEC.parse(ops, encoded.copy()).getOrThrow();
    }
  }

  private final Prototype prototype;
  private final long amount;
  private final double chance;
  private final String selector;
  private final String match;
  private final Prototype preview;
  private final boolean parallelUnaffected;
  private final long minAmount;
  private final long maxAmount;
  private final boolean ignoreOutputCheck;

  public ItemAmount(ItemStack prototype, long amount, double chance, String selector, String match, ItemStack preview, boolean parallelUnaffected, long minAmount, long maxAmount, boolean ignoreOutputCheck) {
    this(Prototype.of(prototype), amount, chance, selector, match, Prototype.of(preview), parallelUnaffected, minAmount,
        maxAmount, ignoreOutputCheck);
  }

  private ItemAmount(Prototype prototype, long amount, double chance, String selector, String match, Prototype preview, boolean parallelUnaffected, long minAmount, long maxAmount, boolean ignoreOutputCheck) {
    if (prototype.stack().isEmpty()
        || prototype.stack().getCount() != 1
        || amount <= 0
        || !Double.isFinite(chance)
        || chance < 0
        || chance > 1
        || !("exact".equals(match) || "item".equals(match))
        || !preview.stack().isEmpty() && preview.stack().getCount() != 1
        || minAmount < 0
        || maxAmount < minAmount
        || maxAmount > 0 && amount != maxAmount) {
      throw new IllegalArgumentException("Expected one prototype and positive long amount");
    }
    this.prototype = prototype;
    this.amount = amount;
    this.chance = chance;
    this.selector = ResourceSelector.require(selector);
    this.match = match;
    this.preview = preview;
    this.parallelUnaffected = parallelUnaffected;
    this.minAmount = minAmount;
    this.maxAmount = maxAmount;
    this.ignoreOutputCheck = ignoreOutputCheck;
  }

  /**
   * Isolates component-bearing Java builder stacks before publishing or sharing
   * this row.
   */
  public ItemAmount capture(HolderLookup.Provider registries) {
    if (!prototype.needsCapture() && !preview.needsCapture()) {
      return this;
    }
    return capture(registries.createSerializationContext(NbtOps.INSTANCE));
  }

  private ItemAmount capture(DynamicOps<Tag> ops) {
    var captured = prototype.capture(ops);
    var capturedPreview = preview.capture(ops);
    if (captured == prototype && capturedPreview == preview) {
      return this;
    }
    return new ItemAmount(
        captured,
        amount,
        chance,
        selector,
        match,
        capturedPreview,
        parallelUnaffected,
        minAmount,
        maxAmount,
        ignoreOutputCheck);
  }

  public ItemAmount withAmountsAndChance(long amount, double chance, long minAmount, long maxAmount) {
    return new ItemAmount(
        prototype,
        amount,
        chance,
        selector,
        match,
        preview,
        parallelUnaffected,
        minAmount,
        maxAmount,
        ignoreOutputCheck);
  }

  public ItemAmount withParallelUnaffected(boolean fixed) {
    return new ItemAmount(
        prototype,
        amount,
        chance,
        selector,
        match,
        preview,
        fixed,
        minAmount,
        maxAmount,
        ignoreOutputCheck);
  }

  public String match() {
    return match;
  }

  public boolean ignoresComponents() {
    return match.equals("item");
  }

  public String selector() {
    return selector;
  }

  public long amount() {
    return amount;
  }

  public long minimumAmount() {
    return maxAmount == 0 ? amount : minAmount;
  }

  public long minAmount() {
    return minAmount;
  }

  public long maxAmount() {
    return maxAmount;
  }

  public ItemStack previewStack() {
    return preview.copy();
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

  public ItemStack prototype(HolderLookup.Provider registries) {
    return ItemStackSnapshots.detached(prototype.stack(), registries);
  }

  /**
   * Client display copy; it is not an execution prototype or mutable recipe
   * state.
   */
  public ItemStack displayStack() {
    return preview.stack().isEmpty() ? prototype.copy() : preview.copy();
  }

  public ItemStack ingredientStack() {
    return prototype.copy();
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof ItemAmount value
            && amount == value.amount
            && chance == value.chance
            && parallelUnaffected == value.parallelUnaffected
            && ignoreOutputCheck == value.ignoreOutputCheck
            && minAmount == value.minAmount
            && maxAmount == value.maxAmount
            && selector.equals(value.selector)
            && match.equals(value.match)
            && ItemStack.matches(prototype.stack(), value.prototype.stack())
            && ItemStack.matches(preview.stack(), value.preview.stack());
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        prototype.stack().getItem(),
        prototype.stack().getComponents(),
        amount,
        chance == 0 ? 0.0D : chance,
        selector,
        match,
        preview.stack().getItem(),
        preview.stack().getComponents(),
        preview.stack().getCount(),
        parallelUnaffected,
        ignoreOutputCheck,
        minAmount,
        maxAmount);
  }
}
