package com.lw.modularmachine.menu;

import java.util.List;
import java.util.function.Consumer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.ApiStatus;

/** Shared legacy player slot order and inventory-only shift-click behavior. */
@ApiStatus.Internal
final class PlayerInventorySlots {
  private PlayerInventorySlots() {
  }

  /**
   * Caller keeps its schema check; this checks physical identity and distance.
   */
  static boolean withinReach(Player player, BlockEntity entity) {
    return entity.getLevel() == player.level()
        && player.level().getBlockEntity(entity.getBlockPos()) == entity
        && player.distanceToSqr(
            entity.getBlockPos().getX() + .5,
            entity.getBlockPos().getY() + .5,
            entity.getBlockPos().getZ() + .5) <= 64;
  }

  static void add(Inventory inventory, Consumer<Slot> addSlot, int x, int inventoryY, int hotbarY) {
    for (int row = 0; row < 3; row++) {
      for (int column = 0; column < 9; column++) {
        addSlot.accept(new Slot(inventory, 9 + row * 9 + column, x + column * 18, inventoryY + row * 18));
      }
    }
    for (int column = 0; column < 9; column++) {
      addSlot.accept(new Slot(inventory, column, x + column * 18, hotbarY));
    }
  }

  @FunctionalInterface
  interface Mover {
    boolean move(ItemStack stack, int start, int end, boolean reverse);
  }

  /** Caller checks menu validity before passing its player-only slot list. */
  static ItemStack quickMove(Player player, int index, List<Slot> slots, Mover mover) {
    if (index < 0 || index >= slots.size() || !slots.get(index).hasItem()) {
      return ItemStack.EMPTY;
    }
    var slot = slots.get(index);
    var original = slot.getItem().copy();
    int start = index < 27 ? 27 : 0;
    int end = index < 27 ? 36 : 27;
    if (!mover.move(slot.getItem(), start, end, false)) {
      return ItemStack.EMPTY;
    }
    if (slot.getItem().isEmpty()) {
      slot.setByPlayer(ItemStack.EMPTY);
    } else {
      slot.setChanged();
    }
    slot.onTake(player, slot.getItem());
    return original;
  }
}
