package com.lw.modularmachine.menu;

import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.network.MachinePayloads;
import com.lw.modularmachine.registry.MachineRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

public final class MachineResourceMenu extends AbstractContainerMenu {
  private final MachinePortBlockEntity port;
  private final Player viewer;
  private MachinePayloads.ResourcePortStatusPayload status;
  private MachinePayloads.ResourcePortStatusPayload lastSentStatus;

  public MachineResourceMenu(int id, Inventory inventory, FriendlyByteBuf data) {
    this(id, inventory, requirePort(inventory, data));
  }

  private static MachinePortBlockEntity requirePort(Inventory inventory, FriendlyByteBuf data) {
    if (inventory.player.level().getBlockEntity(data.readBlockPos()) instanceof MachinePortBlockEntity port) {
      return port;
    }
    throw new IllegalStateException("Missing resource port while opening menu");
  }

  public MachineResourceMenu(int id, Inventory inventory, MachinePortBlockEntity port) {
    super(MachineRegistries.RESOURCE_MENU.get(), id);
    this.port = port;
    this.viewer = inventory.player;
    if (viewer instanceof ServerPlayer) {
      this.lastSentStatus = MachinePayloads.resourceStatus(port);
    }
    PlayerInventorySlots.add(inventory, this::addSlot, 8, 84, 142);
  }

  public MachinePortBlockEntity port() {
    return port;
  }

  @Override
  public boolean clickMenuButton(Player player, int button) {
    return stillValid(player) && GroupInputMenu.open(player, port, button);
  }

  public void acceptStatus(MachinePayloads.ResourcePortStatusPayload status) {
    if (status.position().equals(port.getBlockPos()) && status.kind() == port.kind()) {
      this.status = status;
    }
  }

  public MachinePayloads.ResourcePortStatusPayload status() {
    return status;
  }

  @Override
  public boolean stillValid(Player player) {
    return !port.isRemoved() && port.schemaSupported() && PlayerInventorySlots.withinReach(player, port);
  }

  @Override
  public ItemStack quickMoveStack(Player player, int index) {
    if (!stillValid(player)) {
      return ItemStack.EMPTY;
    }
    return PlayerInventorySlots.quickMove(player, index, slots, this::moveItemStackTo);
  }

  @Override
  public void broadcastChanges() {
    super.broadcastChanges();
    if (!(viewer instanceof ServerPlayer serverPlayer)
        || !(port.getLevel() instanceof ServerLevel)
        || !stillValid(viewer)) {
      return;
    }
    var current = MachinePayloads.resourceStatus(port);
    if (!sameStatus(lastSentStatus, current)) {
      lastSentStatus = current;
      PacketDistributor.sendToPlayer(serverPlayer, current);
    }
  }

  private static boolean sameStatus(MachinePayloads.ResourcePortStatusPayload before, MachinePayloads.ResourcePortStatusPayload after) {
    return before != null
        && before.position().equals(after.position())
        && before.kind() == after.kind()
        && before.amount() == after.amount()
        && before.capacity() == after.capacity()
        && before.transferLimit() == after.transferLimit()
        && before.fluid().equals(after.fluid())
        && before.chemical().equals(after.chemical());
  }
}
