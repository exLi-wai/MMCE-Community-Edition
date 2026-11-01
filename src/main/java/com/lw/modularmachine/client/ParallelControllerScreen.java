package com.lw.modularmachine.client;

import com.lw.modularmachine.menu.ParallelControllerMenu;
import com.lw.modularmachine.network.ParallelControllerPayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import org.lwjgl.glfw.GLFW;

public final class ParallelControllerScreen extends AbstractContainerScreen<ParallelControllerMenu> {
  private static final ResourceLocation TEXTURE = ResourceLocation
      .fromNamespaceAndPath("mmcece", "textures/gui/guismartinterface.png");
  private EditBox input;
  private ParallelControllerPayloads.Status displayed;

  public ParallelControllerScreen(ParallelControllerMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = 176;
    imageHeight = 166;
  }

  @Override
  protected void init() {
    super.init();
    displayed = null;
    input = addRenderableWidget(
        new EditBox(font, leftPos + 73, topPos + 48, 95, 10, Component.translatable("menu.mmcece.parallel.input")));
    input.setMaxLength(10);
    input.setFilter(text -> text.matches("[0-9]*"));
    int[] steps = {1, 10, 100};
    int[] columns = {7, 72, 139};
    for (int index = 0; index < steps.length; index++) {
      int step = steps[index];
      addRenderableWidget(
          Button.builder(Component.literal("-" + step), button -> change(-step))
              .bounds(leftPos + columns[index], topPos + 26, 30, 20).build());
      addRenderableWidget(
          Button.builder(Component.literal("+" + step), button -> change(step))
              .bounds(leftPos + columns[index], topPos + 60, 30, 20).build());
    }
  }

  private void change(int delta) {
    var status = menu.status();
    if (status != null && status.editable()) {
      menu.requestValue((int) Math.clamp((long) status.value() + delta, 0, status.maximum()));
    }
  }

  @Override
  protected void containerTick() {
    super.containerTick();
    var current = menu.status();
    if (current != null && current != displayed) {
      displayed = current;
      input.setValue("");
      input.setFocused(false);
    }
    input.setEditable(current != null && current.editable());
    for (var child : children()) {
      if (child instanceof Button button) {
        button.active = current != null && current.editable();
      }
    }
  }

  @Override
  public boolean keyPressed(int key, int scanCode, int modifiers) {
    if (input.isFocused() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
      try {
        menu.requestValue(Integer.parseInt(input.getValue()));
      } catch (NumberFormatException ignored) {
      }
      input.setValue("");
      input.setFocused(false);
      return true;
    }
    if (input.isFocused() && key != GLFW.GLFW_KEY_ESCAPE) {
      return input.keyPressed(key, scanCode, modifiers);
    }

    return super.keyPressed(key, scanCode, modifiers);
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
    graphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, 256, 256);
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    graphics.drawString(font, Component.translatable("menu.mmcece.parallel.title"), 4, 4, 0xFFFFFF, true);
    var status = menu.status();
    if (status != null) {
      graphics.drawString(
          font,
          Component.translatable("menu.mmcece.parallel.maximum", status.maximum()),
          6,
          16,
          0xFFFFFF,
          true);
      graphics.drawString(
          font,
          Component.translatable("menu.mmcece.parallel.current", status.value()),
          6,
          49,
          0xFFFFFF,
          true);
    }
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    super.render(graphics, mouseX, mouseY, partialTick);
    renderTooltip(graphics, mouseX, mouseY);
  }
}
