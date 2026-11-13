package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.machine.recipe.ResourceSelector;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.NbtOps;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.ApiStatus;

/** Exact fluid and persistent components, independent of quantity. */
@ApiStatus.Internal
public final class FluidResourceKey {
  private final FluidStack prototype;
  private final int hash;
  private final String selector;

  public FluidResourceKey(FluidStack source, HolderLookup.Provider registries) {
    if (source.isEmpty()) {
      throw new IllegalArgumentException("Empty fluid cannot identify an output");
    }
    var ops = registries.createSerializationContext(NbtOps.INSTANCE);
    var encoded = FluidStack.CODEC.encodeStart(ops, source.copyWithAmount(1)).getOrThrow();
    prototype = FluidStack.CODEC.parse(ops, encoded.copy()).getOrThrow();
    if (!FluidStack.matches(source.copyWithAmount(1), prototype)) {
      throw new IllegalArgumentException("Lossy fluid components cannot be planned");
    }
    selector = "";
    hash = 31 * (31 * System.identityHashCode(prototype.getFluid()) + prototype.getComponents().hashCode());
  }

  private FluidResourceKey(FluidResourceKey source, String selector) {
    prototype = source.prototype;
    this.selector = ResourceSelector.require(selector);
    hash = 31 * (31 * System.identityHashCode(prototype.getFluid()) + prototype.getComponents().hashCode()) + selector
        .hashCode();
  }

  public FluidResourceKey withSelector(String selector) {
    return this.selector.equals(selector) ? this : new FluidResourceKey(this, selector);
  }

  public String selector() {
    return selector;
  }

  public FluidStack amount(int amount) {
    return prototype.copyWithAmount(amount);
  }

  @Override
  public int hashCode() {
    return hash;
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof FluidResourceKey key
            && selector.equals(key.selector)
            && FluidStack.isSameFluidSameComponents(prototype, key.prototype);
  }
}
