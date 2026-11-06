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
  private static final ResourceLocation DETAILS_BUTTON = ResourceLocation
      .fromNamespaceAndPath("mmcece", "textures/gui/button.png");
  private final ClientMachineJeiCatalog.Machine machine;
  private final float centerX, centerY, centerZ, baseScale;
  private boolean dragging;
  private float yaw = -135, pitch = 25, zoom = 1, panX, panY;
  private boolean cycle = true;
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
    int width = getArea().width();
    int height = getArea().height();
    int buttonHeight = detailsButtonHeight(height);
    int previewHeight = Math.max(1, height - buttonHeight);
    float scale = Math.max(.1F, Math.min(24F, Math.min(width, previewHeight) * .55F / structureExtent()) * zoom);
    enablePreviewScissor(graphics);
    graphics.pose().pushPose();
    graphics.pose().translate(width / 2F + panX, previewHeight / 2F + panY, 150);
    graphics.pose().scale(scale, -scale, scale);
    graphics.pose()
        .mulPose(new Quaternionf().rotationXYZ((float) Math.toRadians(pitch), (float) Math.toRadians(yaw), 0));
    graphics.pose().translate(-centerX, -centerY, -centerZ);
    Lighting.setupFor3DItems();
    graphics.flush();
    var buffers = mc.renderBuffers().bufferSource();
    if (cycle) {
      cycleTick = Util.getMillis() / 1500;
    }
    int tick = (int) cycleTick;
    try (var colorScope = PreviewBlockView.pushMachineColor(machine.color())) {
      for (var part : machine.parts()) {
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

    drawDetailsButton(graphics, mouseX, mouseY, width, height, buttonHeight);
  }

  private float structureExtent() {
    float minX = machine.parts().stream().mapToInt(part -> part.offset().getX()).min().orElse(0);
    float maxX = machine.parts().stream().mapToInt(part -> part.offset().getX()).max().orElse(0);
    float minY = machine.parts().stream().mapToInt(part -> part.offset().getY()).min().orElse(0);
    float maxY = machine.parts().stream().mapToInt(part -> part.offset().getY()).max().orElse(0);
    float minZ = machine.parts().stream().mapToInt(part -> part.offset().getZ()).min().orElse(0);
    float maxZ = machine.parts().stream().mapToInt(part -> part.offset().getZ()).max().orElse(0);
    return Math.max(maxX - minX + 1, Math.max(maxY - minY + 1, maxZ - minZ + 1));
  }

  private static int detailsButtonHeight(int height) {
    return Math.min(20, Math.max(14, height / 11));
  }

  private static int detailsButtonLeft(int width) {
    return Math.max(0, width / 2 - Math.max(1, width - 16) / 2);
  }

  private static int detailsButtonWidth(int width) {
    return Math.max(1, width - 16);
  }

  private static void drawDetailsButton(GuiGraphics graphics, double mouseX, double mouseY, int width, int height, int buttonHeight) {
    int buttonWidth = detailsButtonWidth(width);
    int left = detailsButtonLeft(width);
    int top = height - buttonHeight;
    boolean hovered = mouseX >= left && mouseX < left + buttonWidth && mouseY >= top && mouseY < height;
    graphics.blit(DETAILS_BUTTON, left, top, buttonWidth, buttonHeight, 0, 0, 200, 20, 200, 20);
    Minecraft minecraft = Minecraft.getInstance();
    int color = hovered ? 0xFF202020 : 0xFF404040;
    Component text = Component.translatable("jei.mmcece.view_details");
    int textX = left + (buttonWidth - minecraft.font.width(text)) / 2;
    int textY = top + Math.max(0, (buttonHeight - minecraft.font.lineHeight) / 2);
    graphics.drawString(minecraft.font, text, textX, textY, color, false);
  }

  /**
   * JEI invokes this widget with the recipe-area transform already on the pose
   * stack. GuiGraphics.enableScissor consumes screen coordinates and does not
   * apply that transform, so convert the whole local recipe area first.
   */
  private void enablePreviewScissor(GuiGraphics graphics) {
    var pose = new Matrix4f(graphics.pose().last().pose());
    var topLeft = pose.transformPosition(new Vector3f(0, 0, 0));
    var bottomRight = pose.transformPosition(
        new Vector3f(getArea().width(), getArea().height() - detailsButtonHeight(getArea().height()), 0));
    int left = (int) Math.floor(Math.min(topLeft.x, bottomRight.x));
    int top = (int) Math.floor(Math.min(topLeft.y, bottomRight.y));
    int right = (int) Math.ceil(Math.max(topLeft.x, bottomRight.x));
    int bottom = (int) Math.ceil(Math.max(topLeft.y, bottomRight.y));
    graphics.enableScissor(left, top, right, bottom);
  }

  @Override
  public boolean mouseClicked(double x, double y, int button) {
    int width = getArea().width();
    int height = getArea().height();
    int buttonHeight = detailsButtonHeight(height);
    int buttonWidth = detailsButtonWidth(width);
    int buttonLeft = detailsButtonLeft(width);
    if (x >= buttonLeft && x < buttonLeft + buttonWidth && y >= height - buttonHeight && y < height && button == 0) {
      Minecraft.getInstance().setScreen(new MachineDetailsScreen(machine));
      return true;
    }
    dragging = inPreview(x, y);
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
    if (!inPreview(x, y)) {
      return false;
    }
    zoom = Math.max(.1F, Math.min(8, zoom * (float) Math.pow(1.1, scrollY)));
    return true;
  }
  @Override
  public void getTooltip(ITooltipBuilder tooltip, double x, double y) {
    int width = getArea().width();
    int height = getArea().height();
    int buttonHeight = detailsButtonHeight(height);
    int buttonWidth = detailsButtonWidth(width);
    int buttonLeft = detailsButtonLeft(width);
    if (x >= buttonLeft && x < buttonLeft + buttonWidth && y >= height - buttonHeight && y < height) {
      tooltip.add(Component.translatable("jei.mmcece.view_details"));
      return;
    }
  }

  private boolean inPreview(double x, double y) {
    return x >= 0
        && x < getArea().width()
        && y >= 0
        && y < getArea().height() - detailsButtonHeight(getArea().height());
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
