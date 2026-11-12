package com.lw.modularmachine.menu;

import com.lw.modularmachine.blockentity.NetworkPortBlockEntity;
import com.lw.modularmachine.machine.network.NetworkPortResource;
import com.lw.modularmachine.network.NetworkPortPayloads;
import com.lw.modularmachine.registry.MachineRegistries;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Network configuration and read-only buffers; inventory clicks never move
 * buffer contents.
 */
public final class NetworkPortMenu extends AbstractContainerMenu {
  private final NetworkPortBlockEntity port;
  private final Player viewer;
  private final UUID session = UUID.randomUUID();
  private long sequence;
  private NetworkPortPayloads.Status status;
  private NetworkPortPayloads.Status lastSent;

  public NetworkPortMenu(int id, Inventory inventory, FriendlyByteBuf data) {
    this(id, inventory, requirePort(inventory, data));
  }

  private static NetworkPortBlockEntity requirePort(Inventory inventory, FriendlyByteBuf data) {
    if (inventory.player.level().getBlockEntity(data.readBlockPos()) instanceof NetworkPortBlockEntity port) {
      return port;
    }
    throw new IllegalStateException("Missing network port while opening menu");
  }

  public NetworkPortMenu(int id, Inventory inventory, NetworkPortBlockEntity port) {
    super(MachineRegistries.NETWORK_PORT_MENU.get(), id);
    this.port = port;
    viewer = inventory.player;
    int inventoryY = port.kind().isInput() ? 124 : 113;
    for (int row = 0; row < 3; row++) {
      for (int col = 0; col < 9; col++) {
        addSlot(new Slot(inventory, 9 + row * 9 + col, 8 + col * 18, inventoryY + row * 18));
      }
    }
    for (int col = 0; col < 9; col++) {
      addSlot(new Slot(inventory, col, 8 + col * 18, inventoryY + 58));
    }
  }

  public NetworkPortBlockEntity port() {
    return port;
  }

  public NetworkPortPayloads.Status status() {
    return status;
  }

  public void acceptStatus(NetworkPortPayloads.Status value) {
    if (value.containerId() == containerId
        && value.position().equals(port.getBlockPos())
        && value.snapshot().resource() == port.kind().resource()
        && value.snapshot().input() == port.kind().isInput()
        && (status == null || status.session().equals(value.session()) && value.sequence() > status.sequence())) {
      status = value;
    }
  }

  public void request(NetworkPortPayloads.Operation operation, int slot, int amount) {
    if (status != null && status.editable() && status.snapshot().input()) {
      PacketDistributor.sendToServer(
          new NetworkPortPayloads.Action(
              containerId,
              port.getBlockPos(),
              status.session(),
              status.snapshot().configRevision(),
              operation,
              slot,
              amount));
    }
  }

  public void apply(ServerPlayer player, NetworkPortPayloads.Action action) {
    if (currentAction(player, action.containerId(), action.position(), action.session(), action.revision())) {
      switch (action.operation()) {
        case SELECT_CARRIED -> port.sampleConfiguration(getCarried().copy()).ifPresent(resource -> {
          if (resource.kind() == port.kind().resource()) {
            port.configure(action.slot(), resource, action.amount(), action.revision());
          }
        });
        case SET_AMOUNT -> {
          var configured = port.snapshot().rows().get(action.slot()).configured();
          if (configured != null) {
            port.configure(action.slot(), configured, action.amount(), action.revision());
          }
        }
        case CLEAR -> port.configure(action.slot(), null, 0, action.revision());
      }
    }
    lastSent = null;
    broadcastChanges();
  }

  public void requestGhost(int slot, NetworkPortResource resource, UUID expectedSession, long expectedRevision) {
    if (status != null
        && status.editable()
        && status.snapshot().input()
        && slot >= 0
        && slot < 9
        && status.session().equals(expectedSession)
        && status.snapshot().configRevision() == expectedRevision
        && resource.kind() == status.snapshot().resource()) {
      PacketDistributor.sendToServer(
          new NetworkPortPayloads.GhostAction(
              containerId,
              port.getBlockPos(),
              expectedSession,
              expectedRevision,
              slot,
              resource));
    }
  }

  public void apply(ServerPlayer player, NetworkPortPayloads.GhostAction action) {
    if (currentAction(player, action.containerId(), action.position(), action.session(), action.revision())
        && action.resource().kind() == port.kind().resource()) {
      int amount = Math.max(1, port.snapshot().rows().get(action.slot()).target());
      port.configure(action.slot(), action.resource(), amount, action.revision());
    }
    lastSent = null;
    broadcastChanges();
  }

  private boolean currentAction(ServerPlayer player, int expectedContainer, BlockPos position, UUID expectedSession, long revision) {
    return player.containerMenu == this
        && expectedContainer == containerId
        && position.equals(port.getBlockPos())
        && expectedSession.equals(session)
        && revision == port.configRevision()
        && canEdit(player);
  }

  private boolean canEdit(Player player) {
    return port.kind().isInput()
        && stillValid(player)
        && !player.isSpectator()
        && player.mayBuild()
        && player.level().mayInteract(player, port.getBlockPos())
        && port.networkReadyToEdit();
  }

  @Override
  public boolean stillValid(Player player) {
    return !port.isRemoved()
        && port.schemaSupported()
        && port.getLevel() == player.level()
        && player.level().getBlockEntity(port.getBlockPos()) == port
        && player.distanceToSqr(port.getBlockPos().getCenter()) <= 64;
  }

  @Override
  public void broadcastChanges() {
    if (port.resourceTransactionInProgress()) {
      return;
    }
    super.broadcastChanges();
    if (viewer instanceof ServerPlayer player && player.containerMenu == this && stillValid(player)) {
      var snapshot = port.snapshot();
      boolean editable = canEdit(player);
      if (lastSent == null || !lastSent.snapshot().equals(snapshot) || lastSent.editable() != editable) {
        lastSent = new NetworkPortPayloads.Status(
            containerId,
            port.getBlockPos(),
            session,
            ++sequence,
            editable,
            snapshot);
        PacketDistributor.sendToPlayer(player, lastSent);
      }
    }
  }

  @Override
  public boolean clickMenuButton(Player player, int button) {
    return stillValid(player) && GroupInputMenu.open(player, port, button);
  }

  @Override
  public ItemStack quickMoveStack(Player player, int index) {
    if (!stillValid(player) || index < 0 || index >= slots.size() || !slots.get(index).hasItem()) {
      return ItemStack.EMPTY;
    }
    var slot = slots.get(index);
    var original = slot.getItem().copy();
    int start = index < 27 ? 27 : 0;
    int end = index < 27 ? 36 : 27;
    if (!moveItemStackTo(slot.getItem(), start, end, false)) {
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
