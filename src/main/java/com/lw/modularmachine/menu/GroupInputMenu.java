package com.lw.modularmachine.menu;

import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.network.GroupInputPayloads;
import com.lw.modularmachine.registry.MachineRegistries;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server-owned group configuration; editing never moves inventory contents. */
public final class GroupInputMenu extends AbstractContainerMenu {
  public static final int OPEN_BUTTON = 100;
  private final MachinePortBlockEntity port;
  private final Player viewer;
  private final UUID session = UUID.randomUUID();
  private GroupInputPayloads.Status status;
  private GroupInputPayloads.Status lastSent;

  public GroupInputMenu(int id, Inventory inventory, FriendlyByteBuf data) {
    this(id, inventory, requirePort(inventory, data));
  }

  private static MachinePortBlockEntity requirePort(Inventory inventory, FriendlyByteBuf data) {
    if (inventory.player.level().getBlockEntity(data.readBlockPos()) instanceof MachinePortBlockEntity port
        && port.canGroupInput()) {
      return port;
    }
    throw new IllegalStateException("Missing groupable input port while opening menu");
  }

  public GroupInputMenu(int id, Inventory inventory, MachinePortBlockEntity port) {
    super(MachineRegistries.GROUP_INPUT_MENU.get(), id);
    this.port = port;
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

  public static boolean open(Player player, MachinePortBlockEntity port, int button) {
    if (button != OPEN_BUTTON || !port.canGroupInput() || !valid(player, port)) {
      return false;
    }
    if (player instanceof ServerPlayer serverPlayer) {
      serverPlayer.openMenu(
          new SimpleMenuProvider(
              (id, inventory, viewer) -> new GroupInputMenu(id, inventory, port),
              Component.translatable("menu.mmcece.group.title")),
          port.getBlockPos());
    }

    return true;
  }

  public MachinePortBlockEntity port() {
    return port;
  }

  public GroupInputPayloads.Status status() {
    return status;
  }

  public void acceptStatus(GroupInputPayloads.Status value) {
    if (value.containerId() == containerId
        && value.position().equals(port.getBlockPos())
        && (status == null || status.session().equals(value.session()) && value.revision() >= status.revision())) {
      status = value;
    }
  }

  public void requestGroup(int group) {
    if (status != null && status.editable()) {
      PacketDistributor.sendToServer(
          new GroupInputPayloads.Action(containerId, port.getBlockPos(), status.session(), status.revision(), group));
    }
  }

  public void apply(ServerPlayer player, GroupInputPayloads.Action action) {
    if (action.containerId() == containerId
        && action.position().equals(port.getBlockPos())
        && action.session().equals(session)
        && canEdit(player)) {
      port.configureInputGroup(action.group(), action.revision());
    }
    lastSent = null;
    broadcastChanges();
  }

  private boolean canEdit(Player player) {
    return stillValid(player)
        && !player.isSpectator()
        && player.mayBuild()
        && player.level().mayInteract(player, port.getBlockPos());
  }

  private static boolean valid(Player player, MachinePortBlockEntity port) {
    return !port.isRemoved()
        && port.schemaSupported()
        && port.getLevel() == player.level()
        && player.level().getBlockEntity(port.getBlockPos()) == port
        && player.distanceToSqr(port.getBlockPos().getCenter()) <= 64;
  }

  @Override
  public boolean stillValid(Player player) {
    return port.canGroupInput() && valid(player, port);
  }

  @Override
  public void broadcastChanges() {
    super.broadcastChanges();
    if (viewer instanceof ServerPlayer serverPlayer && stillValid(viewer)) {
      var current = new GroupInputPayloads.Status(
          containerId,
          port.getBlockPos(),
          session,
          port.groupRevision(),
          port.inputGroup(),
          port.preferredInputGroup(),
          canEdit(viewer));
      if (!current.equals(lastSent)) {
        lastSent = current;
        PacketDistributor.sendToPlayer(serverPlayer, current);
      }
    }
  }

  @Override
  public boolean clickMenuButton(Player player, int button) {
    if (button != 0 || !stillValid(player)) {
      return false;
    }
    if (player instanceof ServerPlayer serverPlayer) {
      serverPlayer.openMenu(port, port.getBlockPos());
    }

    return true;
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
