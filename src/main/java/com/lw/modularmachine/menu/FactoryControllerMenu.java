package com.lw.modularmachine.menu;

import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.network.MachinePayloads;
import com.lw.modularmachine.registry.MachineRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;

public final class FactoryControllerMenu extends ControllerBlueprintMenu {
  private final FactoryControllerBlockEntity factory;
  private final Player viewer;
  private MachinePayloads.FactoryStatusPayload status;
  private MachinePayloads.FactoryStatusPayload lastSentStatus;

  public FactoryControllerMenu(int id, Inventory inventory, FriendlyByteBuf data) {
    this(id, inventory, requireFactory(inventory, data));
  }

  private static FactoryControllerBlockEntity requireFactory(Inventory inventory, FriendlyByteBuf data) {
    if (inventory.player.level().getBlockEntity(data.readBlockPos()) instanceof FactoryControllerBlockEntity factory) {
      return factory;
    }
    throw new IllegalStateException("Missing factory controller while opening menu");
  }

  public FactoryControllerMenu(int id, Inventory inventory, FactoryControllerBlockEntity factory) {
    super(MachineRegistries.FACTORY_CONTROLLER_MENU.get(), id, inventory, factory.blueprintInventory(), 112, 255);
    this.factory = factory;
    this.viewer = inventory.player;
  }

  public FactoryControllerBlockEntity factory() {
    return factory;
  }

  public void acceptStatus(MachinePayloads.FactoryStatusPayload status) {
    if (status.position().equals(factory.getBlockPos())) {
      this.status = status;
    }
  }

  public MachinePayloads.FactoryStatusPayload status() {
    return status;
  }

  public void sendLockAction(String threadId, ResourceLocation recipeId) {
    sendThreadAction(threadId, MachinePayloads.FactoryAction.SET_LOCKED_RECIPE, recipeId, false);
  }

  public void sendEnabledAction(String threadId, boolean enabled) {
    sendThreadAction(threadId, MachinePayloads.FactoryAction.SET_ENABLED, null, enabled);
  }

  public void sendParallelLimitAction(String threadId, int parallelLimit, long upgradeRevision) {
    if (viewer.level() == null || !viewer.level().isClientSide || status == null || threadId == null) {
      return;
    }
    var thread = status.threads().stream().filter(candidate -> candidate.threadId().equals(threadId)).findFirst()
        .orElse(null);
    if (thread == null) {
      return;
    }
    PacketDistributor.sendToServer(
        new MachinePayloads.FactoryThreadActionPayload(
            MachinePayloads.PROTOCOL_VERSION,
            factory.getBlockPos(),
            threadId,
            thread.revision(),
            MachinePayloads.FactoryAction.SET_PARALLEL_LIMIT,
            null,
            thread.enabled(),
            parallelLimit,
            upgradeRevision));
  }

  private void sendThreadAction(String threadId, MachinePayloads.FactoryAction action, ResourceLocation recipeId, boolean enabled) {
    if (viewer.level() == null || !viewer.level().isClientSide || status == null || threadId == null) {
      return;
    }
    var thread = status.threads().stream().filter(candidate -> candidate.threadId().equals(threadId)).findFirst()
        .orElse(null);
    if (thread == null) {
      return;
    }
    PacketDistributor.sendToServer(
        new MachinePayloads.FactoryThreadActionPayload(
            MachinePayloads.PROTOCOL_VERSION,
            factory.getBlockPos(),
            threadId,
            thread.revision(),
            action,
            recipeId,
            enabled,
            thread.parallelLimit(),
            thread.upgradeRevision()));
  }

  @Override
  public boolean stillValid(Player player) {
    return !factory.isRemoved()
        && factory.persistentStateValid()
        && factory.getLevel() == player.level()
        && player.level().getBlockEntity(factory.getBlockPos()) == factory
        && player.distanceToSqr(
            factory.getBlockPos().getX() + .5,
            factory.getBlockPos().getY() + .5,
            factory.getBlockPos().getZ() + .5) <= 64;
  }

  @Override
  public void broadcastChanges() {
    super.broadcastChanges();
    if (!(viewer instanceof ServerPlayer serverPlayer) || !stillValid(viewer)) {
      return;
    }
    var current = MachinePayloads.factoryStatus(factory);
    if (!current.equals(lastSentStatus)) {
      lastSentStatus = current;
      PacketDistributor.sendToPlayer(serverPlayer, current);
    }
  }
}
