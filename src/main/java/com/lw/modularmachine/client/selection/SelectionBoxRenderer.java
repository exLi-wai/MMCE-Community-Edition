package com.lw.modularmachine.client.selection;

import com.lw.modularmachine.item.RangedConstructToolItem;
import com.lw.modularmachine.machine.selection.RangedSelectionService;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Box;
import com.lw.modularmachine.machine.selection.RangedSelectionData.PickTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
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
    var state = RangedSelectionClient.snapshot();
    if (state == null || !state.dimension().equals(minecraft.level.dimension().location())) {
      return;
    }
    var camera = event.getCamera().getPosition();
    var pose = event.getPoseStack();
    var buffers = minecraft.renderBuffers().bufferSource();
    var consumer = buffers.getBuffer(RenderType.lines());
    pose.pushPose();
    try {
      pose.translate(-camera.x, -camera.y, -camera.z);
      if (!state.append()) {
        draw(pose, consumer, state.main(), .3F, .85F, 1F, 1F);
      }
      for (int index = 0; index < state.segments().size(); index++) {
        var segment = state.segments().get(index);
        boolean selected = index == state.selected();
        draw(pose, consumer, segment.body(), 1F, .7F, .15F, alpha(selected, state.picking(), PickTarget.BODY));
        draw(pose, consumer, segment.start(), .45F, 1F, .45F, alpha(selected, state.picking(), PickTarget.START));
        draw(pose, consumer, segment.end(), 1F, .4F, .75F, alpha(selected, state.picking(), PickTarget.END));
      }
      if (state.first() != null) {
        draw(pose, consumer, new Box(state.first(), state.first()), 1F, 1F, 1F, 1F);
      }
    } finally {
      pose.popPose();
    }
    buffers.endBatch(RenderType.lines());
  }

  private static float alpha(boolean selected, PickTarget picking, PickTarget target) {
    if (!selected) {
      return .45F;
    }
    return picking == PickTarget.NONE || picking == target ? 1F : .6F;
  }

  private static void draw(PoseStack pose, VertexConsumer consumer, Box selected, float red, float green, float blue, float alpha) {
    if (selected == null) {
      return;
    }
    var min = RangedSelectionService.min(selected.first(), selected.second());
    var max = RangedSelectionService.max(selected.first(), selected.second());
    var box = new AABB(
        min.getX(),
        min.getY(),
        min.getZ(),
        (double) max.getX() + 1,
        (double) max.getY() + 1,
        (double) max.getZ() + 1);
    LevelRenderer.renderLineBox(pose, consumer, box, red, green, blue, alpha);
  }
}
