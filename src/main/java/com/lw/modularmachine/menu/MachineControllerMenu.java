package com.lw.modularmachine.menu;

import com.lw.modularmachine.api.controller.MachineControllerApi;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.network.MachinePayloads;
import com.lw.modularmachine.network.ControllerGuiDataPayloads;
import com.lw.modularmachine.registry.MachineRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;

public final class MachineControllerMenu extends ControllerBlueprintMenu {
  private final MachineControllerBlockEntity controller;
  private final Player viewer;
  private MachinePayloads.ControllerStatusPayload status;
  private MachinePayloads.ControllerStatusPayload lastSentStatus;
  private String guiCustomData = "{}";
  private String lastSentGuiData;

  public String guiCustomData() {
    return guiCustomData;
  }

  public void acceptGuiData(ControllerGuiDataPayloads.Update update) {
    if (update.containerId() == containerId
        && controller.getLevel() != null
        && update.dimension().equals(controller.getLevel().dimension().location())
        && update.position().equals(controller.getBlockPos())
        && update.machine().equals(controller.machineId())) {
      guiCustomData = update.customData();
    }
  }

  public MachineControllerMenu(int id, Inventory inventory, FriendlyByteBuf data) {
    this(id, inventory, requireController(inventory, data));
  }

  private static MachineControllerBlockEntity requireController(Inventory inventory, FriendlyByteBuf data) {
    if (inventory.player.level()
        .getBlockEntity(data.readBlockPos()) instanceof MachineControllerBlockEntity controller) {
      return controller;
    }
    throw new IllegalStateException("Missing machine controller while opening menu");
  }

  public MachineControllerMenu(int id, Inventory inventory, MachineControllerBlockEntity controller) {
    super(MachineRegistries.CONTROLLER_MENU.get(), id, inventory, controller.blueprintInventory(), 8, 151);
    this.controller = controller;
    this.viewer = inventory.player;
  }

  public MachineControllerBlockEntity controller() {
    return controller;
  }

  public void acceptStatus(MachinePayloads.ControllerStatusPayload status) {
    if (status.position().equals(controller.getBlockPos())) {
      this.status = status;
    }
  }

  public MachinePayloads.ControllerStatusPayload status() {
    return status;
  }

  @Override
  public boolean stillValid(Player player) {
    return !controller.isRemoved()
        && controller.schemaSupported()
        && controller.getLevel() == player.level()
        && player.level().getBlockEntity(controller.getBlockPos()) == controller
        && player.distanceToSqr(
            controller.getBlockPos().getX() + .5,
            controller.getBlockPos().getY() + .5,
            controller.getBlockPos().getZ() + .5) <= 64;
  }

  @Override
  public void broadcastChanges() {
    super.broadcastChanges();
    if (!(viewer instanceof ServerPlayer serverPlayer) || !stillValid(viewer)) {
      return;
    }
    var current = MachineControllerApi.inspectForMenu(controller).map(MachinePayloads.ControllerStatusPayload::from)
        .orElse(null);
    if (current != null && !current.equals(lastSentStatus)) {
      lastSentStatus = current;
      PacketDistributor.sendToPlayer(serverPlayer, current);
    }
    String data = controller.scriptStateAvailable() ? controller.scriptState().snapshot().customData() : "{}";
    if (!data.equals(lastSentGuiData)) {
      lastSentGuiData = data;
      PacketDistributor.sendToPlayer(
          serverPlayer,
          new ControllerGuiDataPayloads.Update(
              containerId,
              controller.getLevel().dimension().location(),
              controller.getBlockPos(),
              controller.machineId(),
              data));
    }
  }
}
