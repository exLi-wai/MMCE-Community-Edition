package com.lw.modularmachine.integration.jei;

import com.mojang.blaze3d.platform.Lighting;
import com.lw.modularmachine.client.preview.PreviewBlockView;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.inputs.IJeiGuiEventListener;
import mezz.jei.api.gui.widgets.IRecipeWidget;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/**
 * Read-only block-model scene. Per-layout camera state replaces the old JEI
 * reflection/mixins.
 */
final class LegacyStructureJeiWidget implements IRecipeWidget, IJeiGuiEventListener {
  private static final ResourceLocation TEXTURE = ResourceLocation
      .fromNamespaceAndPath("mmcece", "textures/gui/guiblueprint_new.png");
  private final ClientMachineJeiCatalog.Machine machine;
  private final float centerX, centerY, centerZ, baseScale;
  private boolean dragging;
  private float yaw = -135, pitch = 25, zoom = 1, panX, panY;
  private boolean layers, cycle = true, menu = true;
  private long cycleTick = Util.getMillis() / 1500;
  private int layer;
  private final int minY, maxY;
  LegacyStructureJeiWidget(ClientMachineJeiCatalog.Machine machine) {
    this.machine = machine;

    minY = machine.parts().stream().mapToInt(part -> part.offset().getY()).min().orElse(0);
    maxY = machine.parts().stream().mapToInt(part -> part.offset().getY()).max().orElse(0);
    layer = minY;
    float minX = machine.parts().stream().mapToInt(part -> part.offset().getX()).min().orElse(0);
    float maxX = machine.parts().stream().mapToInt(part -> part.offset().getX()).max().orElse(0);
    float minZ = machine.parts().stream().mapToInt(part -> part.offset().getZ()).min().orElse(0);
    float maxZ = machine.parts().stream().mapToInt(part -> part.offset().getZ()).max().orElse(0);
    float extent = Math.max(maxX - minX + 1, Math.max(maxY - minY + 1, maxZ - minZ + 1));
    centerX = (minX + maxX + 1) / 2;
    centerY = (minY + maxY + 1) / 2F;
    centerZ = (minZ + maxZ + 1) / 2;
    baseScale = Math.max(1F, Math.min(24F, Math.round(90F / extent)));
  }
  @Override
  public ScreenPosition getPosition() {
    return new ScreenPosition(0, 0);
  }
  @Override
  public ScreenRectangle getArea() {
    return new ScreenRectangle(0, 0, 184, 220);
  }
  @Override
  public ScreenRectangle getScreenRectangle() {
    return getArea();
  }

  @Override
  public void drawWidget(GuiGraphics graphics, double mouseX, double mouseY) {

    var mc = Minecraft.getInstance();
    var title = Component.translatable(machine.translation());
    var titleLines = mc.font.split(title, 136);
    if (!titleLines.isEmpty()) {
      var line = titleLines.getFirst();
      // Legacy MultiLineLabel centers its 10px line inside the 18px title row.
      int titleY = 5 + (18 - 10) / 2;
      graphics.drawString(mc.font, line, 43 + Math.max(0, (136 - mc.font.width(line)) / 2), titleY, 0xFFFFFFFF, true);
    }
    graphics.drawString(
        mc.font,
        layers ? Component.translatable("jei.mmcece.legacy.layer", layer) : Component.literal("3D"),
        8,
        28,
        0xFFFFFFFF,
        true);
    float scale = baseScale * zoom;
    enablePreviewScissor(graphics);
    graphics.pose().pushPose();
    graphics.pose().translate(92 + panX, 100 + panY, 150);
    graphics.pose().scale(scale, -scale, scale);
    graphics.pose().mulPose(
        new Quaternionf()
            .rotationXYZ((float) Math.toRadians(layers ? 90 : pitch), (float) Math.toRadians(layers ? 0 : yaw), 0));
    graphics.pose().translate(-centerX, layers ? -layer - .5F : -centerY, -centerZ);
    Lighting.setupFor3DItems();
    graphics.flush();
    var buffers = mc.renderBuffers().bufferSource();
    if (cycle) {
      cycleTick = Util.getMillis() / 1500;
    }
    int tick = (int) cycleTick;
    try (var colorScope = PreviewBlockView.pushMachineColor(machine.color())) {
      for (var part : machine.parts()) {
        if (layers && part.offset().getY() != layer) {
          continue;
        }
        var state = part.alternatives().get(Math.floorMod(tick, part.alternatives().size()));
        graphics.pose().pushPose();
        graphics.pose().translate(part.offset().getX(), part.offset().getY(), part.offset().getZ());
        try {
          mc.getBlockRenderer()
              .renderSingleBlock(state, graphics.pose(), buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        } finally {
          graphics.pose().popPose();
        }
      }
    } finally {
      try {
        buffers.endBatch();
      } finally {
        graphics.pose().popPose();
        graphics.disableScissor();
        Lighting.setupFor3DItems();
      }
    }

    button(graphics, 165, 161, 15, menu, mouseX, mouseY);
    if (menu) {
      button(graphics, 150, 161, 30, layers, mouseX, mouseY);
      button(graphics, 135, 161, 105, cycle, mouseX, mouseY);
    }
    button(graphics, 165, 28, 229, false, mouseX, mouseY);
  }

  /**
   * JEI invokes this widget with the recipe-area transform already on the pose
   * stack. GuiGraphics.enableScissor consumes screen coordinates and does not
   * apply that transform, so convert the local preview rectangle first.
   */
  private static void enablePreviewScissor(GuiGraphics graphics) {
    var pose = new Matrix4f(graphics.pose().last().pose());
    var topLeft = pose.transformPosition(new Vector3f(6, 26, 0));
    var bottomRight = pose.transformPosition(new Vector3f(178, 176, 0));
    int left = (int) Math.floor(Math.min(topLeft.x, bottomRight.x));
    int top = (int) Math.floor(Math.min(topLeft.y, bottomRight.y));
    int right = (int) Math.ceil(Math.max(topLeft.x, bottomRight.x));
    int bottom = (int) Math.ceil(Math.max(topLeft.y, bottomRight.y));
    graphics.enableScissor(left, top, right, bottom);
  }

  private static void button(GuiGraphics graphics, int x, int y, int v, boolean selected, double mouseX, double mouseY) {
    int u = 184 + (selected ? 30 : mouseX >= x && mouseX < x + 13 && mouseY >= y && mouseY < y + 13 ? 15 : 0);
    graphics.blit(TEXTURE, x, y, u, v, 13, 13);
  }
  @Override
  public boolean mouseClicked(double x, double y, int button) {
    if (y >= 161 && y < 174) {
      if (x >= 165 && x < 178) {
        menu = !menu;
        return true;
      }
      if (menu && x >= 150 && x < 163) {
        layers = !layers;
        return true;
      }
      if (menu && x >= 135 && x < 148) {
        cycle = !cycle;
        return true;
      }
    }
    if (x >= 165 && x < 178 && y >= 28 && y < 41) {
      yaw = -135;
      pitch = 25;
      zoom = 1;
      panX = 0;
      panY = 0;
      return true;
    }
    dragging = x >= 6 && x < 178 && y >= 26 && y < 176;
    return dragging;
  }
  @Override
  public boolean mouseReleased(double x, double y, int button) {
    boolean handled = dragging;
    dragging = false;
    return handled;
  }
  @Override
  public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
    if (!dragging) {
      return false;
    }
    if (button == 0) {
      yaw += dx * .25F;
      pitch = Math.max(-90, Math.min(90, pitch + (float) dy * .25F));
    } else {
      panX += dx;
      panY += dy;
    }
    return true;
  }
  @Override
  public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
    if (x < 6 || x >= 178 || y < 26 || y >= 176) {
      return false;
    }
    if (layers) {
      layer = Math.max(minY, Math.min(maxY, layer + (int) Math.signum(scrollY)));
    } else {
      zoom = Math.max(.1F, Math.min(8, zoom * (float) Math.pow(1.1, scrollY)));
    }
    return true;
  }
  @Override
  public void getTooltip(ITooltipBuilder tooltip, double x, double y) {
    if (x >= 165 && x < 178 && y >= 28 && y < 41) {
      tooltip.add(Component.translatable("gui.preview.button.reset_center.tip"));
    }
    if (y >= 161 && y < 174) {
      if (x >= 165 && x < 178) {
        tooltip.add(Component.translatable("gui.preview.button.menu.tip"));
      } else if (menu && x >= 150 && x < 163) {
        tooltip.add(
            Component.translatable(
                layers ? "gui.preview.button.toggle_3d_render.tip" : "gui.preview.button.toggle_layer_render.tip"));
      } else if (menu && x >= 135 && x < 148) {
        tooltip.add(
            Component.translatable(
                cycle ? "gui.preview.button.disable_cycle_replaceable_blocks.tip" : "gui.preview.button.enable_cycle_replaceable_blocks.tip.0"));
        if (!cycle) {
          tooltip.add(Component.translatable("gui.preview.button.enable_cycle_replaceable_blocks.tip.1"));
        }
      }
    }
  }
  @Override
  public boolean keyPressed(double x, double y, int key, int scan, int modifiers) {
    if (x >= 0 && x < 184 && y >= 0 && y < 26 && key == GLFW.GLFW_KEY_A) {
      MachineJeiPlugin.bookmark(MachineJeiPlugin.blueprint(machine.id()));
      return true;
    }
    return false;
  }
}
