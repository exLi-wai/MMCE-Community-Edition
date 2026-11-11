package com.lw.modularmachine.machine.controller;

import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal legacy protection for player destruction, without GUI ownership
 * locks.
 */
@ApiStatus.Internal
@EventBusSubscriber(modid = "mmcece")
public final class ControllerOwnershipEvents {
  private ControllerOwnershipEvents() {
  }

  @SubscribeEvent
  public static void breaking(BlockEvent.BreakEvent event) {
    if (event.isCanceled()
        || !MachineConfig.securitySystemEnabled()
        || !(event.getLevel() instanceof ServerLevel level)) {
      return;
    }
    ServerThreadGuard.require(level, "Controller ownership protection");
    if (!level.hasChunkAt(event.getPos())
        || !(level.getBlockEntity(event.getPos()) instanceof ControllerOwnershipSubject controller)) {
      return;
    }
    var player = event.getPlayer();
    if (!controller.ownershipState().permits(player.getUUID(), player.isCreative(), true)) {
      event.setCanceled(true);
      player.displayClientMessage(Component.translatable("message.mmcece.controller_owner_break_denied"), true);
    }
  }
}
