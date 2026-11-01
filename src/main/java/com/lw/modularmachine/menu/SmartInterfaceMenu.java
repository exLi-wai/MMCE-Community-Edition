package com.lw.modularmachine.menu;

import com.lw.modularmachine.blockentity.SmartInterfaceBlockEntity;
import com.lw.modularmachine.network.SmartInterfacePayloads;
import com.lw.modularmachine.registry.MachineRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

public final class SmartInterfaceMenu extends AbstractContainerMenu {
  private final SmartInterfaceBlockEntity smart;
  private final Player viewer;
  private SmartInterfacePayloads.Status status;
  private SmartInterfacePayloads.Status lastSent;

  public SmartInterfaceMenu(int id, Inventory inventory, FriendlyByteBuf data) {
    this(id, inventory, require(inventory, data));
  }

  private static SmartInterfaceBlockEntity require(Inventory inventory, FriendlyByteBuf data) {
    if (inventory.player.level().getBlockEntity(data.readBlockPos()) instanceof SmartInterfaceBlockEntity smart) {
      return smart;
    }
    throw new IllegalStateException("Missing smart interface while opening menu");
  }

  public SmartInterfaceMenu(int id, Inventory inventory, SmartInterfaceBlockEntity smart) {
    super(MachineRegistries.SMART_INTERFACE_MENU.get(), id);
    this.smart = smart;
    viewer = inventory.player;
    for (int row = 0; row < 3; row++) {
      for (int col = 0; col < 9; col++) {
        addSlot(new Slot(inventory, 9 + row * 9 + col, 8 + col * 18, 84 + row * 18));
      }
    }
    for (int col = 0; col < 9; col++) {
      addSlot(new Slot(inventory, col, 8 + col * 18, 142));
    }
  }

  public SmartInterfaceBlockEntity smart() {
    return smart;
  }

  public SmartInterfacePayloads.Status status() {
    return status;
  }

  public void acceptStatus(SmartInterfacePayloads.Status value) {
    if (value.containerId() == containerId
        && value.position().equals(smart.getBlockPos())
        && (status == null || value.instance().equals(status.instance()) && value.revision() >= status.revision())) {
      status = value;
    }
  }

  public void requestValue(SmartInterfaceBlockEntity.Entry entry, double value) {
    if (status != null) {
      PacketDistributor.sendToServer(
          new SmartInterfacePayloads.Action(
              containerId,
              smart.getBlockPos(),
              status.instance(),
              entry.binding().controller(),
              entry.binding().type(),
              entry.binding().revision(),
              value));
    }
  }

  public void refreshStatus() {
    lastSent = null;
    broadcastChanges();
  }

  @Override
  public void broadcastChanges() {
    super.broadcastChanges();
    if (viewer instanceof ServerPlayer player && player.containerMenu == this && stillValid(viewer)) {
      var value = new SmartInterfacePayloads.Status(
          containerId,
          smart.getBlockPos(),
          smart.instance(),
          smart.revision(),
          smart.schemaSupported(),
          smart.entries());
      if (!value.equals(lastSent)) {
        PacketDistributor.sendToPlayer(player, value);
        lastSent = value;
      }
    }
  }

  @Override
  public boolean stillValid(Player player) {
    return !smart.isRemoved()
        && smart.getLevel() == player.level()
        && player.level().getBlockEntity(smart.getBlockPos()) == smart
        && player.distanceToSqr(
            smart.getBlockPos().getX() + .5,
            smart.getBlockPos().getY() + .5,
            smart.getBlockPos().getZ() + .5) <= 64;
  }

  @Override
  public ItemStack quickMoveStack(Player player, int index) {
    if (!stillValid(player) || index < 0 || index >= slots.size() || !slots.get(index).hasItem()) {
      return ItemStack.EMPTY;
    }
    var slot = slots.get(index);
    var before = slot.getItem().copy();
    boolean moved = index < 27 ? moveItemStackTo(slot.getItem(), 27, 36, false) : moveItemStackTo(
        slot.getItem(),
        0,
        27,
        false);
    if (!moved) {
      return ItemStack.EMPTY;
    }
    if (slot.getItem().isEmpty()) {
      slot.set(ItemStack.EMPTY);
    } else {
      slot.setChanged();
    }
    slot.onTake(player, slot.getItem());

    return before;
  }
}
