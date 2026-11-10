package com.lw.modularmachine.client.preview;

import com.lw.modularmachine.item.MachineConstructionTool;
import com.lw.modularmachine.network.ProjectionLengthPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;

/**
 * Internal input for the held projector; length authority stays on the server.
 */
@EventBusSubscriber(modid = "mmcece", value = Dist.CLIENT)
public final class ProjectionToolInput {
  private ProjectionToolInput() {
  }

  @SubscribeEvent(priority = EventPriority.HIGH)
  public static void scroll(InputEvent.MouseScrollingEvent event) {
    var minecraft = Minecraft.getInstance();
    if (event.isCanceled()
        || event.getScrollDeltaY() == 0
        || minecraft.screen != null
        || minecraft.level == null
        || minecraft.player == null
        || !Screen.hasShiftDown()) {
      return;
    }
    InteractionHand hand;
    if (minecraft.player.getMainHandItem().getItem() instanceof MachineConstructionTool) {
      hand = InteractionHand.MAIN_HAND;
    } else if (minecraft.player.getOffhandItem().getItem() instanceof MachineConstructionTool) {
      hand = InteractionHand.OFF_HAND;
    } else {
      return;
    }
    event.setCanceled(true);
    ProjectionLengthPayloads.adjust(hand, event.getScrollDeltaY() > 0 ? 1 : -1);
  }

}
