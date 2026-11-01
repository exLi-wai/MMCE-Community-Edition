package com.lw.modularmachine.menu;

import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.registry.MachineRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.SlotItemHandler;

public final class MachinePortMenu extends AbstractContainerMenu {
  private final MachinePortBlockEntity port;
  private final int portSlots;
  private final int rows;

  public MachinePortMenu(int id, Inventory inventory, FriendlyByteBuf data) {
    this(id, inventory, requirePort(inventory, data));
  }

  private static MachinePortBlockEntity requirePort(Inventory inventory, FriendlyByteBuf data) {
    if (inventory.player.level().getBlockEntity(data.readBlockPos()) instanceof MachinePortBlockEntity port) {
      return port;
    }
    throw new IllegalStateException("Missing machine port while opening menu");
  }

  public MachinePortMenu(int id, Inventory inventory, MachinePortBlockEntity port) {
    super(MachineRegistries.PORT_MENU.get(), id);
    this.port = port;
    portSlots = port.inventory().getSlots();
    rows = (portSlots + 8) / 9;
    for (int i = 0; i < portSlots; i++) {
      int[] position = itemPosition(port.tier().name(), i);
      addSlot(new SlotItemHandler(port.inventory(), i, position[0], position[1]));
    }
    for (int row = 0; row < 3; row++) {
      for (int col = 0; col < 9; col++) {
        addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
      }
    }
    for (int col = 0; col < 9; col++) {
      addSlot(new Slot(inventory, col, 8 + col * 18, 142));
    }
  }

  public int rows() {
    return rows;
  }

  public MachinePortBlockEntity port() {
    return port;
  }

  @Override
  public boolean clickMenuButton(Player player, int button) {
    return stillValid(player) && GroupInputMenu.open(player, port, button);
  }

  public int portSlots() {
    return portSlots;
  }

  public String tierName() {
    return port.tier().name();
  }

  private static int[] itemPosition(String tier, int index) {
    return switch (tier) {
      case "tiny" -> new int[]{81, 30};
      case "small" -> new int[]{70 + index % 2 * 18, 18 + index / 2 * 18};
      case "normal" -> new int[]{61 + index % 3 * 18, 18 + index / 3 * 18};
      case "reinforced" -> new int[]{61 + index % 3 * 18, 13 + index / 3 * 18};
      case "big" -> new int[]{52 + index % 4 * 18, 18 + index / 4 * 18};
      case "huge" -> new int[]{53 + index % 4 * 18, 8 + index / 4 * 18};
      case "ludicrous" -> new int[]{17 + index % 8 * 18, 8 + index / 8 * 18};
      default -> new int[]{8 + index % 9 * 18, 18 + index / 9 * 18};
    };
  }

  @Override
  public boolean stillValid(Player player) {
    return !port.isRemoved()
        && port.schemaSupported()
        && port.getLevel() == player.level()
        && player.level().getBlockEntity(port.getBlockPos()) == port
        && player.distanceToSqr(
            port.getBlockPos().getX() + .5,
            port.getBlockPos().getY() + .5,
            port.getBlockPos().getZ() + .5) <= 64;
  }

  @Override
  public ItemStack quickMoveStack(Player player, int index) {
    if (!stillValid(player) || index < 0 || index >= slots.size()) {
      return ItemStack.EMPTY;
    }
    Slot slot = slots.get(index);
    if (!slot.hasItem()) {
      return ItemStack.EMPTY;
    }
    ItemStack stack = slot.getItem();
    ItemStack original = stack.copy();
    if (index < portSlots) {
      if (!moveItemStackTo(stack, portSlots, slots.size(), true)) {
        return ItemStack.EMPTY;
      }
    } else {
      if (!moveItemStackTo(stack, 0, portSlots, false)) {
        return ItemStack.EMPTY;
      }
    }
    if (stack.isEmpty()) {
      slot.setByPlayer(ItemStack.EMPTY);
    } else {
      slot.setChanged();
    }
    slot.onTake(player, stack);
    return original;
  }
}
