package com.lw.modularmachine.menu;

import com.lw.modularmachine.blockentity.ParallelControllerBlockEntity;
import com.lw.modularmachine.network.ParallelControllerPayloads;
import com.lw.modularmachine.registry.MachineRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

public final class ParallelControllerMenu extends AbstractContainerMenu {
  private final ParallelControllerBlockEntity parallel;
  private final Player viewer;
  private ParallelControllerPayloads.Status status, lastSent;

  public ParallelControllerMenu(int id, Inventory inventory, FriendlyByteBuf data) {
    this(id, inventory, require(inventory, data));
  }

  private static ParallelControllerBlockEntity require(Inventory inventory, FriendlyByteBuf data) {
    if (inventory.player.level()
        .getBlockEntity(data.readBlockPos()) instanceof ParallelControllerBlockEntity parallel) {
      return parallel;
    }
    throw new IllegalStateException("Missing parallel controller while opening menu");
  }

  public ParallelControllerMenu(int id, Inventory inventory, ParallelControllerBlockEntity parallel) {
    super(MachineRegistries.PARALLEL_CONTROLLER_MENU.get(), id);
    this.parallel = parallel;
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

  public ParallelControllerPayloads.Status status() {
    return status;
  }

  public void acceptStatus(ParallelControllerPayloads.Status value) {
    if (value.containerId() == containerId
        && value.position().equals(parallel.getBlockPos())
        && (status == null || status.instance().equals(value.instance()) && value.revision() >= status.revision())) {
      status = value;
    }
  }

  public void requestValue(int value) {
    if (status != null && status.editable() && value >= 0 && value <= status.maximum()) {
      PacketDistributor.sendToServer(
          new ParallelControllerPayloads.Action(
              containerId,
              parallel.getBlockPos(),
              status.instance(),
              status.revision(),
              status.maximum(),
              value));
    }
  }

  public void apply(ServerPlayer player, ParallelControllerPayloads.Action action) {
    if (action.containerId() == containerId
        && stillValid(player)
        && action.position().equals(parallel.getBlockPos())
        && action.instance().equals(parallel.instance())
        && action.maximum() == parallel.maximum()) {
      parallel.update(player, action.revision(), action.value());
    }
    lastSent = null;
    broadcastChanges();
  }

  @Override
  public boolean stillValid(Player player) {
    return !parallel.isRemoved()
        && parallel.schemaSupported()
        && parallel.getLevel() == player.level()
        && player.level().getBlockEntity(parallel.getBlockPos()) == parallel
        && player.distanceToSqr(parallel.getBlockPos().getCenter()) <= 64;
  }

  @Override
  public void broadcastChanges() {
    super.broadcastChanges();
    if (viewer instanceof ServerPlayer serverPlayer && stillValid(viewer)) {
      boolean editable = !viewer.isSpectator()
          && viewer.mayBuild()
          && viewer.level().mayInteract(viewer, parallel.getBlockPos());
      var current = new ParallelControllerPayloads.Status(
          containerId,
          parallel.getBlockPos(),
          parallel.instance(),
          parallel.revision(),
          parallel.maximum(),
          parallel.parallelism(),
          editable);
      if (!current.equals(lastSent)) {
        lastSent = current;
        PacketDistributor.sendToPlayer(serverPlayer, current);
      }
    }
  }

  @Override
  public ItemStack quickMoveStack(Player player, int index) {
    if (!stillValid(player) || index < 0 || index >= slots.size() || !slots.get(index).hasItem()) {
      return ItemStack.EMPTY;
    }
    var slot = slots.get(index);
    var original = slot.getItem().copy();
    if (!moveItemStackTo(slot.getItem(), index < 27 ? 27 : 0, index < 27 ? 36 : 27, false)) {
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
