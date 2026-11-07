package com.lw.modularmachine.integration.emi;

import com.lw.modularmachine.api.client.preview.PreviewCamera;
import com.lw.modularmachine.api.client.preview.PreviewRenderer;
import com.lw.modularmachine.api.client.preview.PreviewSceneData;
import com.lw.modularmachine.api.client.preview.PreviewViewport;
import com.lw.modularmachine.integration.jei.ClientMachineJeiCatalog;
import com.lw.modularmachine.integration.jei.MachineDetailsScreen;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.api.widget.Widget;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

/** EMI layout and input adapter for the shared structure preview API. */
final class EmiStructurePreviewWidget extends Widget {
  private static final ResourceLocation DETAILS_BUTTON = ResourceLocation
      .fromNamespaceAndPath("mmcece", "textures/gui/button.png");
  private static final int DETAILS_HEIGHT = 20;
  private final int x, y, width, height;
  private final ClientMachineJeiCatalog.Machine machine;
  private final PreviewSceneData scene;
  private final PreviewRenderer renderer = PreviewRenderer.create();
  private final PreviewCamera camera = new PreviewCamera();
  private long lastCycle = Util.getMillis();
  private int step;
  private int dragButton = -1;
  private boolean initialized;

  EmiStructurePreviewWidget(ClientMachineJeiCatalog.Machine machine, int x, int y, int width, int height) {
    this.x = x;
    this.y = y;
    this.width = width;
    this.height = height;
    this.machine = machine;
    scene = new PreviewSceneData(
        machine.color(),
        machine.parts().stream().map(part -> new PreviewSceneData.Entry(part.offset(), part.alternatives(), List.of()))
            .toList());
  }

  @Override
  public Bounds getBounds() {
    return new Bounds(x, y, width, height);
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
    EmiPreviewInput.rendered(this, new Matrix4f(graphics.pose().last().pose()));
    if (!initialized) {
      renderer.setScene(scene);
      initialized = true;
    }
    if (Util.getMillis() - lastCycle >= 1500) {
      step++;
      lastCycle = Util.getMillis();
    }
    int previewHeight = previewHeight();
    float scale = Math.max(.1F, Math.min(24F, Math.min(width, previewHeight) * .55F / scene.extent()));
    renderer.render(
        graphics,
        new PreviewViewport(x, y, width, previewHeight, scale),
        camera,
        PreviewRenderer.ALL_LAYERS,
        step);
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
      camera.rotate(dx, dy);

    } else {
      camera.pan(dx, dy);

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
    camera.zoomBy(amount);
    return true;
  }

  void release() {
    stopDragging();
    renderer.close();

  }

  @Override
  public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    return false;
  }
}
