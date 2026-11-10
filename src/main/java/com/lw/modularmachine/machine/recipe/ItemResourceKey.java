package com.lw.modularmachine.machine.recipe;

import java.util.Objects;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.ApiStatus;

/**
 * Item resource identity, with an explicit item-only input mode. Physical slot
 * keys and output keys retain exact components; prototypes are never exposed.
 */
public final class ItemResourceKey {
  private final ItemStack prototype;
  private final int hash;
  private final int maxStackSize;
  private final String selector;
  private final boolean ignoresComponents;

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
    ignoresComponents = false;
    hash = 31 * (31 * System.identityHashCode(prototype.getItem()) + prototype.getComponents().hashCode());
  }

  private ItemResourceKey(ItemResourceKey source, String selector, boolean ignoresComponents) {
    prototype = source.prototype;
    maxStackSize = source.maxStackSize;
    this.selector = ResourceSelector.require(selector);
    this.ignoresComponents = ignoresComponents;
    int componentsHash = 0;
    if (!ignoresComponents) {
      componentsHash = prototype.getComponents().hashCode();
    }
    int keyHash = 31 * (31 * System.identityHashCode(prototype.getItem()) + componentsHash) + selector.hashCode();
    if (ignoresComponents) {
      keyHash++;
    }
    hash = keyHash;
  }

  public ItemResourceKey withSelector(String selector) {
    ResourceSelector.require(selector);

    return this.selector.equals(selector) ? this : new ItemResourceKey(this, selector, ignoresComponents);
  }

  /** Logical input alias; never use this key as a physical debit or output. */
  @ApiStatus.Internal
  public ItemResourceKey ignoringComponents() {
    return ignoresComponents ? this : new ItemResourceKey(this, selector, true);
  }

  @ApiStatus.Internal
  public boolean ignoresComponents() {
    return ignoresComponents;
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
    var name = ignoresComponents ? prototype.getItem().toString() + " [match=item]" : prototype.toString();

    return selector.isEmpty() ? name : name + " [" + selector + "]";
  }

  /** Read-only client localization key; no stack materialization is needed. */
  @ApiStatus.Internal
  public String descriptionId() {
    return prototype.getItem().getDescriptionId();
  }

  public ItemStack stack(int count) {
    if (count < 0 || count > maxStackSize()) {
      throw new IllegalArgumentException("Count exceeds this item's component-aware stack limit");
    }
    return count == 0 ? ItemStack.EMPTY : ItemStackSnapshots.detached(prototype, serverRegistries())
        .copyWithCount(count);
  }

  public boolean matches(ItemStack stack) {
    if (stack.isEmpty()) {
      return false;
    }
    if (ignoresComponents) {
      return stack.is(prototype.getItem());
    }

    return ItemStack.isSameItemSameComponents(prototype, stack);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ItemResourceKey key)
        || !selector.equals(key.selector)
        || ignoresComponents != key.ignoresComponents) {
      return false;
    }
    if (ignoresComponents) {
      return prototype.is(key.prototype.getItem());
    }

    return ItemStack.isSameItemSameComponents(prototype, key.prototype);
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
