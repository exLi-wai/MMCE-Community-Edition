package com.lw.modularmachine.machine.recipe;

import java.util.Objects;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Exact item plus components, independent of stack count; never exposes its
 * mutable prototype.
 */
public final class ItemResourceKey {
  private final ItemStack prototype;
  private final int hash;
  private final int maxStackSize;
  private final String selector;

  private ItemResourceKey(ItemStack stack, HolderLookup.Provider registries) {
    if (stack.isEmpty()) {
      throw new IllegalArgumentException("Empty stacks do not have a resource key");
    }
    prototype = ItemStackSnapshots.detached(stack.copyWithCount(1), registries);
    maxStackSize = prototype.getMaxStackSize();
    if (maxStackSize <= 0) {
      throw new IllegalArgumentException("Invalid component-aware stack limit");
    }
    selector = "";
    hash = 31 * (31 * System.identityHashCode(prototype.getItem()) + prototype.getComponents().hashCode());
  }

  private ItemResourceKey(ItemResourceKey source, String selector) {
    prototype = source.prototype;
    maxStackSize = source.maxStackSize;
    this.selector = ResourceSelector.require(selector);
    hash = 31 * (31 * System.identityHashCode(prototype.getItem()) + prototype.getComponents().hashCode()) + selector
        .hashCode();
  }

  public ItemResourceKey withSelector(String selector) {
    ResourceSelector.require(selector);

    return this.selector.equals(selector) ? this : new ItemResourceKey(this, selector);
  }

  public String selector() {
    return selector;
  }

  /**
   * Server-thread convenience; registry-aware loaders may supply their captured
   * lookup explicitly.
   */
  public static ItemResourceKey of(ItemStack stack) {
    return of(stack, serverRegistries());
  }

  public static ItemResourceKey of(ItemStack stack, HolderLookup.Provider registries) {
    return new ItemResourceKey(
        Objects.requireNonNull(stack, "stack"),
        Objects.requireNonNull(registries, "registries"));
  }

  public int maxStackSize() {
    return maxStackSize;
  }

  public String debugName() {
    return selector.isEmpty() ? prototype.toString() : prototype + " [" + selector + "]";
  }

  public ItemStack stack(int count) {
    if (count < 0 || count > maxStackSize()) {
      throw new IllegalArgumentException("Count exceeds this item's component-aware stack limit");
    }
    return count == 0 ? ItemStack.EMPTY : ItemStackSnapshots.detached(prototype, serverRegistries())
        .copyWithCount(count);
  }

  public boolean matches(ItemStack stack) {
    return !stack.isEmpty() && ItemStack.isSameItemSameComponents(prototype, stack);
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof ItemResourceKey key
            && selector.equals(key.selector)
            && ItemStack.isSameItemSameComponents(prototype, key.prototype);
  }

  @Override
  public int hashCode() {
    return hash;
  }

  @Override
  public String toString() {
    return debugName();
  }

  private static HolderLookup.Provider serverRegistries() {
    var server = ServerLifecycleHooks.getCurrentServer();
    if (server == null || !server.isSameThread()) {
      throw new IllegalStateException("Item key capture/materialization outside server thread");
    }
    return server.registryAccess();
  }
}
