package com.lw.modularmachine.machine.recipe;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;

/**
 * Capture-time round trip: ItemStack.copy can share mutable names or custom
 * component payloads.
 */
public final class ItemStackSnapshots {
  private ItemStackSnapshots() {
  }

  public static ItemStack detached(ItemStack stack, HolderLookup.Provider registries) {
    if (stack.isEmpty()) {
      return ItemStack.EMPTY;
    }
    var result = ItemStack.parse(registries, stack.save(registries).copy())
        .orElseThrow(() -> new IllegalArgumentException("Cannot snapshot item and components"));
    // Network-only/nonpersistent components must never disappear into an
    // ID-equivalent key.
    if (!ItemStack.matches(stack, result)) {
      throw new IllegalArgumentException("Lossy or nonpersistent item components cannot be planned");
    }
    return result;
  }
}
