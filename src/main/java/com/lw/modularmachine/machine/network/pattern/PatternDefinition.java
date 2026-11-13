package com.lw.modularmachine.machine.network.pattern;

import com.lw.modularmachine.machine.recipe.ItemStackSnapshots;
import java.util.Objects;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal full pattern item definition, not its output or decoded equality.
 */
@ApiStatus.Internal
public final class PatternDefinition {
  private final CompoundTag encoded;

  private PatternDefinition(CompoundTag encoded) {
    this.encoded = encoded.copy();
  }

  public static PatternDefinition capture(ItemStack stack, HolderLookup.Provider registries) {
    if (Objects.requireNonNull(stack, "stack").isEmpty() || stack.getCount() != 1) {
      throw new IllegalArgumentException("A pattern slot requires exactly one pattern");
    }
    var detached = ItemStackSnapshots.detached(stack, registries);

    return new PatternDefinition((CompoundTag) detached.save(registries));
  }

  public static PatternDefinition load(CompoundTag tag, HolderLookup.Provider registries) {
    var item = ItemStack.parse(registries, tag.copy())
        .orElseThrow(() -> new IllegalArgumentException("Invalid saved pattern item"));
    var definition = capture(item, registries);
    if (!definition.encoded.equals(tag)) {
      throw new IllegalArgumentException("Noncanonical saved pattern item");
    }

    return definition;
  }

  public ItemStack stack(HolderLookup.Provider registries) {
    return ItemStack.parse(registries, encoded.copy()).orElseThrow();
  }

  public CompoundTag save() {
    return encoded.copy();
  }

  @Override
  public boolean equals(Object other) {
    return this == other || other instanceof PatternDefinition value && encoded.equals(value.encoded);
  }

  @Override
  public int hashCode() {
    return encoded.hashCode();
  }
}
