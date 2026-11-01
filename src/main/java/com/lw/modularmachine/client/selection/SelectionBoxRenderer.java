package com.lw.modularmachine.client.selection;

import com.lw.modularmachine.item.RangedConstructToolItem;
import com.lw.modularmachine.machine.selection.RangedSelectionService;
import com.lw.modularmachine.registry.MachineDataComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Draws the ranged construct tool's two corners as a world-space outline. */
@EventBusSubscriber(modid = "mmcece", value = Dist.CLIENT)
public final class SelectionBoxRenderer {
  private SelectionBoxRenderer() {
  }

  @SubscribeEvent
  public static void render(RenderLevelStageEvent event) {
    if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
      return;
    }
    var minecraft = Minecraft.getInstance();
    var player = minecraft.player;
    if (player == null || minecraft.level == null) {
      return;
    }
    var stack = player.getMainHandItem();
    if (!(stack.getItem() instanceof RangedConstructToolItem)) {
      return;
    }
    var first = stack.get(MachineDataComponents.SELECTION_FIRST.get());
    if (first == null) {
      return;
    }
    var second = stack.get(MachineDataComponents.SELECTION_SECOND.get());
    var other = second == null ? first : second;
    var min = RangedSelectionService.min(first, other);
    var max = RangedSelectionService.max(first, other).offset(1, 1, 1);
    var box = new AABB(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
    var camera = event.getCamera().getPosition();
    var pose = event.getPoseStack();
    var buffers = minecraft.renderBuffers().bufferSource();
    var consumer = buffers.getBuffer(RenderType.lines());
    pose.pushPose();
    pose.translate(-camera.x, -camera.y, -camera.z);
    LevelRenderer.renderLineBox(pose, consumer, box, .3F, .85F, 1F, 1F);
    pose.popPose();
    buffers.endBatch(RenderType.lines());
  }
}
