package com.lw.modularmachine.integration.jei;

import com.lw.modularmachine.api.client.preview.PreviewCamera;
import com.lw.modularmachine.api.client.preview.PreviewRenderer;
import com.lw.modularmachine.api.client.preview.PreviewSceneData;
import com.lw.modularmachine.api.client.preview.PreviewViewport;
import com.lw.modularmachine.util.client.gui.GuiTextures;
import com.lw.modularmachine.util.minecraft.registry.RegisteredMachineItems;
import java.util.List;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.inputs.IJeiGuiEventListener;
import mezz.jei.api.gui.widgets.IRecipeWidget;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

/** JEI layout and input adapter for the shared structure preview API. */
final class LegacyStructureJeiWidget implements IRecipeWidget, IJeiGuiEventListener {
  private static final GuiTextures.NineSlice DETAILS_BUTTON = new GuiTextures.NineSlice(
      ResourceLocation.fromNamespaceAndPath("mmcece", "textures/gui/button.png"),
      0,
      0,
      200,
      20,
      200,
      20,
      2);
  private final ClientMachineJeiCatalog.Machine machine;
  private final PreviewSceneData scene;
  private final PreviewRenderer renderer = PreviewRenderer.create();
  private final PreviewCamera camera = new PreviewCamera();
  private boolean dragging, initialized;

  LegacyStructureJeiWidget(ClientMachineJeiCatalog.Machine machine) {
    this.machine = machine;
    scene = new PreviewSceneData(
        machine.color(),
        machine.parts().stream().map(part -> new PreviewSceneData.Entry(part.offset(), part.alternatives(), List.of()))
            .toList());
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
    if (!initialized) {
      renderer.setScene(scene);
      initialized = true;
    }
    int width = getArea().width();
    int height = getArea().height();
    int buttonHeight = detailsButtonHeight(height);
    int previewHeight = Math.max(1, height - buttonHeight);
    float baseScale = Math.max(.1F, Math.min(24F, Math.min(width, previewHeight) * .55F / scene.extent()));
    renderer.render(
        graphics,
        new PreviewViewport(0, 0, width, previewHeight, baseScale),
        camera,
        PreviewRenderer.ALL_LAYERS,
        (int) (Util.getMillis() / 1500));
    drawDetailsButton(graphics, mouseX, mouseY, width, height, buttonHeight);
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
    GuiTextures.blitNineSlice(graphics, DETAILS_BUTTON, left, top, buttonWidth, buttonHeight);
    Minecraft minecraft = Minecraft.getInstance();
    int color = hovered ? 0xFF202020 : 0xFF404040;
    Component text = Component.translatable("jei.mmcece.view_details");
    int textX = left + (buttonWidth - minecraft.font.width(text)) / 2;
    int textY = top + Math.max(0, (buttonHeight - minecraft.font.lineHeight) / 2);
    graphics.drawString(minecraft.font, text, textX, textY, color, false);
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
      camera.rotate(dx, dy);

    } else {
      camera.pan(dx, dy);

    }
    return true;
  }
  @Override
  public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
    if (!inPreview(x, y)) {
      return false;
    }
    camera.zoomBy(scrollY);
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
      MachineJeiPlugin.bookmark(RegisteredMachineItems.blueprint(machine.id()));
      return true;
    }
    return false;
  }
}
