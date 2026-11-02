package com.lw.modularmachine.menu;

import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import com.lw.modularmachine.network.UpgradeBusPayloads;
import com.lw.modularmachine.registry.MachineRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.SlotItemHandler;
import net.neoforged.neoforge.network.PacketDistributor;

public final class UpgradeBusMenu extends AbstractContainerMenu {
  private final UpgradeBusBlockEntity bus;
  private final Player viewer;
  private UpgradeBusPayloads.Status status;
  private UpgradeBusPayloads.Status lastSentStatus;

  public UpgradeBusMenu(int id, Inventory inventory, FriendlyByteBuf data) {
    this(id, inventory, requireBus(inventory, data));
  }

  private static UpgradeBusBlockEntity requireBus(Inventory inventory, FriendlyByteBuf data) {
    if (inventory.player.level().getBlockEntity(data.readBlockPos()) instanceof UpgradeBusBlockEntity bus) {
      return bus;
    }
    throw new IllegalStateException("Missing upgrade bus while opening menu");
  }

  public UpgradeBusMenu(int id, Inventory inventory, UpgradeBusBlockEntity bus) {
    super(MachineRegistries.UPGRADE_BUS_MENU.get(), id);
    this.bus = bus;
    viewer = inventory.player;
    for (int i = 0; i < bus.slotCount(); i++) {
      addSlot(new SlotItemHandler(bus.inventory(), i, 8 + i % 3 * 18, 17 + i / 3 * 18));
    }
    for (int row = 0; row < 3; row++) {
      for (int col = 0; col < 9; col++) {
        addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 131 + row * 18));
      }
    }
    for (int col = 0; col < 9; col++) {
      addSlot(new Slot(inventory, col, 8 + col * 18, 189));
    }
  }

  public UpgradeBusBlockEntity bus() {
    return bus;
  }

  public int upgradeSlots() {
    return bus.tier().slots();
  }

  public UpgradeBusPayloads.Status status() {
    return status;
  }

  public void acceptStatus(UpgradeBusPayloads.Status next) {
    if (next.containerId() == containerId
        && next.position().equals(bus.getBlockPos())
        && (long) next.entries().size() + next.incompatible().size() <= upgradeSlots()
        && next.bindings().stream()
            .allMatch(binding -> (long) binding.entries().size() + binding.incompatible().size() <= upgradeSlots())
        && (status == null
            || next.revision() >= status.revision() && next.bindingRevision() >= status.bindingRevision())) {
      status = next;
    }
  }

  @Override
  public void broadcastChanges() {
    super.broadcastChanges();
    if (!(viewer instanceof ServerPlayer serverPlayer) || serverPlayer.containerMenu != this || !stillValid(viewer)) {
      return;
    }
    var current = UpgradeBusPayloads.capture(containerId, bus);
    if (!current.equals(lastSentStatus)) {
      PacketDistributor.sendToPlayer(serverPlayer, current);
      lastSentStatus = current;
    }
  }

  @Override
  public boolean stillValid(Player player) {
    return !bus.isRemoved()
        && bus.schemaSupported()
        && bus.getLevel() == player.level()
        && player.level().getBlockEntity(bus.getBlockPos()) == bus
        && player.distanceToSqr(
            bus.getBlockPos().getX() + .5,
            bus.getBlockPos().getY() + .5,
            bus.getBlockPos().getZ() + .5) <= 64;
  }

  @Override
  public ItemStack quickMoveStack(Player player, int index) {
    if (!stillValid(player) || index < 0 || index >= slots.size()) {
      return ItemStack.EMPTY;
    }
    var slot = slots.get(index);
    if (!slot.hasItem()) {
      return ItemStack.EMPTY;
    }
    var original = slot.getItem().copy();
    int busSlots = bus.tier().slots();
    if (index < busSlots ? !moveItemStackTo(slot.getItem(), busSlots, slots.size(), true) : !moveItemStackTo(
        slot.getItem(),
        0,
        busSlots,
        false)) {
      return ItemStack.EMPTY;
    }
    if (index < busSlots) {
      bus.inventory().setStackInSlot(index, slot.getItem().copy());
    } else {
      slot.setChanged();
    }
    slot.onTake(player, slot.getItem());
    return original;
  }
}
