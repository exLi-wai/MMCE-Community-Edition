package com.lw.modularmachine.integration.emi;

import com.lw.modularmachine.client.preview.PreviewRenderScene;
import com.lw.modularmachine.client.preview.SectionMeshCache;
import com.lw.modularmachine.integration.jei.ClientMachineJeiCatalog;
import com.lw.modularmachine.integration.jei.MachineDetailsScreen;
import com.lw.modularmachine.network.ProjectionPayloads;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.api.widget.Widget;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

final class EmiStructurePreviewWidget extends Widget {
  private static final ResourceLocation DETAILS_BUTTON = ResourceLocation
      .fromNamespaceAndPath("mmcece", "textures/gui/button.png");
  private static final int DETAILS_HEIGHT = 20;
  private final int x, y, width, height;
  private final ClientMachineJeiCatalog.Machine machine;
  private final float centerX, centerY, centerZ, extent;
  private final PreviewRenderScene scene;
  private final SectionMeshCache cache = new SectionMeshCache();
  private long lastCycle = Util.getMillis();
  private int step;
  private float yaw = -135F, pitch = 25F, zoom = 1F, panX, panY;
  private int dragButton = -1;
  private boolean cacheReady;

  EmiStructurePreviewWidget(ClientMachineJeiCatalog.Machine machine, int x, int y, int width, int height) {
    this.x = x;
    this.y = y;
    this.width = width;
    this.height = height;
    this.machine = machine;
    var entries = machine.parts().stream()
        .map(part -> new ProjectionPayloads.Entry(part.offset(), part.alternatives(), List.of())).toList();
    scene = PreviewRenderScene
        .create(0, ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), machine.id(), entries);
    int minX = entries.stream().mapToInt(entry -> entry.position().getX()).min().orElse(0);
    int maxX = entries.stream().mapToInt(entry -> entry.position().getX()).max().orElse(0);
    int minY = entries.stream().mapToInt(entry -> entry.position().getY()).min().orElse(0);
    int maxY = entries.stream().mapToInt(entry -> entry.position().getY()).max().orElse(0);
    int minZ = entries.stream().mapToInt(entry -> entry.position().getZ()).min().orElse(0);
    int maxZ = entries.stream().mapToInt(entry -> entry.position().getZ()).max().orElse(0);
    centerX = (minX + maxX + 1) / 2F;
    centerY = (minY + maxY + 1) / 2F;
    centerZ = (minZ + maxZ + 1) / 2F;
    extent = Math.max(maxX - minX + 1, Math.max(maxY - minY + 1, maxZ - minZ + 1));
  }

  @Override
  public Bounds getBounds() {
    return new Bounds(x, y, width, height);
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
    var layout = new Matrix4f(graphics.pose().last().pose());
    EmiPreviewInput.rendered(this, layout);
    if (!cacheReady) {
      cache.setPreviewScene(scene);
      cache.setPreviewState(Integer.MIN_VALUE, step);
      cacheReady = true;
    }
    if (Util.getMillis() - lastCycle >= 1500 && !scene.animated().isEmpty()) {
      step++;
      lastCycle = Util.getMillis();
      cache.setPreviewState(Integer.MIN_VALUE, step);
    }
    int previewHeight = previewHeight();
    float scale = Math.max(.1F, Math.min(24F, Math.min(width, previewHeight) * .55F / extent)) * zoom;
    var topLeft = layout.transformPosition(new Vector3f(x, y, 0));
    var bottomRight = layout.transformPosition(new Vector3f(x + width, y + previewHeight, 0));
    graphics.flush();
    graphics.enableScissor(
        (int) Math.floor(Math.min(topLeft.x, bottomRight.x)),
        (int) Math.floor(Math.min(topLeft.y, bottomRight.y)),
        (int) Math.ceil(Math.max(topLeft.x, bottomRight.x)),
        (int) Math.ceil(Math.max(topLeft.y, bottomRight.y)));
    graphics.pose().pushPose();
    try {
      graphics.pose().translate(x + width / 2F + panX, y + previewHeight / 2F + panY, 150);
      graphics.pose().scale(scale, -scale, scale);
      graphics.pose()
          .mulPose(new Quaternionf().rotationXYZ((float) Math.toRadians(pitch), (float) Math.toRadians(yaw), 0));
      graphics.pose().translate(-centerX, -centerY, -centerZ);
      var view = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(graphics.pose().last().pose());
      cache.drawPreview(view, RenderSystem.getProjectionMatrix());
    } finally {
      graphics.pose().popPose();
      graphics.disableScissor();
    }
    int buttonLeft = x + 8;
    int buttonTop = y + height - DETAILS_HEIGHT;
    int buttonWidth = Math.max(1, width - 16);
    boolean hovered = mouseX >= buttonLeft
        && mouseX < buttonLeft + buttonWidth
        && mouseY >= buttonTop
        && mouseY < buttonTop + DETAILS_HEIGHT;
    graphics.blit(DETAILS_BUTTON, buttonLeft, buttonTop, buttonWidth, DETAILS_HEIGHT, 0, 0, 200, 20, 200, 20);
    var text = Component.translatable("jei.mmcece.view_details");
    var font = Minecraft.getInstance().font;
    graphics.drawString(
        font,
        text,
        buttonLeft + (buttonWidth - font.width(text)) / 2,
        buttonTop + Math.max(0, (DETAILS_HEIGHT - font.lineHeight) / 2),
        hovered ? 0xFF202020 : 0xFF404040,
        false);
  }

  @Override
  public boolean mouseClicked(int mouseX, int mouseY, int button) {
    int buttonLeft = x + 8;
    int buttonTop = y + height - DETAILS_HEIGHT;
    int buttonWidth = Math.max(1, width - 16);
    if (button == 0
        && mouseX >= buttonLeft
        && mouseX < buttonLeft + buttonWidth
        && mouseY >= buttonTop
        && mouseY < buttonTop + DETAILS_HEIGHT) {
      Minecraft.getInstance().setScreen(new MachineDetailsScreen(machine));
      return true;
    }
    if (inPreview(mouseX, mouseY)
        && (button == GLFW.GLFW_MOUSE_BUTTON_LEFT || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
      dragButton = button;
      return true;
    }
    return false;
  }

  private int previewHeight() {
    return Math.max(1, height - DETAILS_HEIGHT - 4);
  }

  private boolean inPreview(double mouseX, double mouseY) {
    return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + previewHeight();
  }

  boolean isDragging() {
    return dragButton != -1;
  }

  boolean drag(int button, double dx, double dy) {
    if (dragButton != button) {
      return false;
    }
    if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
      yaw += dx * .25F;
      pitch = Math.max(-90F, Math.min(90F, pitch + (float) dy * .25F));
    } else {
      panX += dx;
      panY += dy;
    }
    return true;
  }

  boolean releaseButton(int button) {
    if (dragButton != button) {
      return false;
    }
    stopDragging();
    return true;
  }

  void stopDragging() {
    dragButton = -1;
  }

  boolean scroll(double mouseX, double mouseY, double amount) {
    if (!inPreview(mouseX, mouseY)) {
      return false;
    }
    zoom = Math.max(.1F, Math.min(8F, zoom * (float) Math.pow(1.1, amount)));
    return true;
  }

  void release() {
    stopDragging();
    cache.close();
    cacheReady = false;
  }

  @Override
  public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    return false;
  }
}
