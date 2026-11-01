package com.lw.modularmachine.menu;

import com.lw.modularmachine.item.MachineBlueprintItem;
import com.lw.modularmachine.storage.ControllerBlueprintInventory;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.SlotItemHandler;

/** Shared legacy slot order: 27 inventory, nine hotbar, then the blueprint. */
public abstract class ControllerBlueprintMenu extends AbstractContainerMenu {
  protected ControllerBlueprintMenu(MenuType<?> type, int id, Inventory inventory, ControllerBlueprintInventory blueprints, int playerX, int blueprintX) {
    super(type, id);
    for (int row = 0; row < 3; row++) {
      for (int column = 0; column < 9; column++) {
        addSlot(new Slot(inventory, column + row * 9 + 9, playerX + column * 18, 131 + row * 18));
      }
    }
    for (int column = 0; column < 9; column++) {
      addSlot(new Slot(inventory, column, playerX + column * 18, 189));
    }
    addSlot(new SlotItemHandler(blueprints, 0, blueprintX, 8) {
      @Override
      public void setChanged() {
        blueprints.markChanged();
      }
    });
  }

  @Override
  public ItemStack quickMoveStack(Player player, int index) {
    if (index < 0 || index >= slots.size()) {
      return ItemStack.EMPTY;
    }
    Slot slot = slots.get(index);
    if (!slot.hasItem() || !slot.mayPickup(player)) {
      return ItemStack.EMPTY;
    }
    ItemStack stack = slot.getItem();
    ItemStack before = stack.copy();
    if (index == 36) {
      if (!moveItemStackTo(stack, 0, 36, false)) {
        return ItemStack.EMPTY;
      }
    } else {
      if (stack.getItem() instanceof MachineBlueprintItem && slots.get(36).mayPlace(stack)) {
        moveItemStackTo(stack, 36, 37, false);
      }
      if (!stack.isEmpty()) {
        if (index < 27) {
          moveItemStackTo(stack, 27, 36, false);
        } else {
          moveItemStackTo(stack, 0, 27, false);
        }
      }
    }
    if (stack.getCount() == before.getCount()) {
      return ItemStack.EMPTY;
    }
    slot.setByPlayer(stack.isEmpty() ? ItemStack.EMPTY : stack);
    slot.onTake(player, stack);

    return before;
  }
}
