package com.lw.modularmachine.util.minecraft.inventory;

import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.items.wrapper.RangedWrapper;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal main-inventory returns using component-aware insertion and the
 * normal toss event.
 */
@ApiStatus.Internal
public final class PlayerInventoryItems {
  private PlayerInventoryItems() {
  }

  /**
   * Takes ownership of a detached stack and empties the supplied stack. Never
   * pass a live inventory stack. The caller owns menu synchronization.
   */
  public static void returnOrDrop(ServerPlayer player, int preferredSlot, ItemStack stack) {
    ServerThreadGuard.require(player.serverLevel(), "Player inventory item return");
    Objects.requireNonNull(stack, "stack");
    if (stack.isEmpty()) {
      return;
    }
    var inventory = player.getInventory();
    // PlayerMainInvWrapper broadcasts after each insertion; this path leaves that
    // to its owner.
    var main = new RangedWrapper(new InvWrapper(inventory), 0, inventory.items.size());
    ItemStack remaining = stack.copyAndClear();
    if (preferredSlot >= 0 && preferredSlot < main.getSlots()) {
      remaining = main.insertItem(preferredSlot, remaining, false);
    }
    if (!remaining.isEmpty()) {
      remaining = ItemHandlerHelper.insertItemStacked(main, remaining, false);
    }
    if (!remaining.isEmpty()) {
      player.drop(remaining, false);
    }
  }
}
