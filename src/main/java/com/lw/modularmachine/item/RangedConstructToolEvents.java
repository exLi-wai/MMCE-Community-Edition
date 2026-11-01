package com.lw.modularmachine.item;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Controllers open their own GUI before an item's useOn, so the ranged tool
 * takes the interaction at the block-event stage.
 */
@EventBusSubscriber(modid = "mmcece")
public final class RangedConstructToolEvents {
  private RangedConstructToolEvents() {
  }

  @SubscribeEvent(priority = EventPriority.LOWEST)
  public static void rightClick(PlayerInteractEvent.RightClickBlock event) {
    if (event.isCanceled() || !(event.getItemStack().getItem() instanceof RangedConstructToolItem)) {
      return;
    }
    if (event.getHand() != InteractionHand.MAIN_HAND) {
      return;
    }
    var level = event.getLevel();
    if (!level.hasChunkAt(event.getPos())) {
      return;
    }
    event.setCanceled(true);
    event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
    if (level.isClientSide) {
      return;
    }
    RangedConstructToolItem.interact(
        level,
        event.getEntity(),
        event.getEntity().getMainHandItem(),
        event.getPos(),
        InteractionHand.MAIN_HAND);
  }
}
