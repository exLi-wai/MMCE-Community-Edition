package com.lw.modularmachine.client;

import com.lw.modularmachine.menu.GroupInputMenu;
import com.lw.modularmachine.network.GroupInputPayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import org.lwjgl.glfw.GLFW;

/** Original 176x166 group configuration layout and shared legacy texture. */
public final class GroupInputScreen extends AbstractContainerScreen<GroupInputMenu> {
  private static final ResourceLocation TEXTURE = ResourceLocation
      .fromNamespaceAndPath("mmcece", "textures/gui/guismartinterface.png");
  private EditBox input;
  private Button toggle;
  private GroupInputPayloads.Status displayed;

  public GroupInputScreen(GroupInputMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = 176;
    imageHeight = 166;
  }

  @Override
  protected void init() {
    super.init();
    displayed = null;
    input = addRenderableWidget(
        new EditBox(font, leftPos + 73, topPos + 48, 95, 10, Component.translatable("menu.mmcece.group.value")));
    input.setMaxLength(10);
    input.setFilter(value -> value.matches("[0-9]*"));
    toggle = addRenderableWidget(
        Button.builder(Component.empty(), button -> toggle()).bounds(leftPos + 119, topPos + 60, 50, 20).build());
    addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> {
      if (minecraft != null && minecraft.gameMode != null) {
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 0);
      }
    }).bounds(leftPos + imageWidth, topPos + 4, 40, 20).build());
    syncStatus();
  }

  @Override
  protected void containerTick() {
    super.containerTick();
    syncStatus();
  }

  private void syncStatus() {
    var current = menu.status();
    if (current != null && current != displayed) {
      displayed = current;
      input.setValue(Integer.toString(current.preferredGroup()));
      input.setFocused(false);
    }
    boolean editable = current != null && current.editable();
    input.setEditable(editable && current.group() > 0);
    toggle.active = editable;
    toggle.setMessage(
        Component.translatable(
            current != null && current.group() > 0 ? "menu.mmcece.group.disable" : "menu.mmcece.group.enable"));
  }

  private void toggle() {
    var status = menu.status();
    if (status != null && status.editable()) {
      menu.requestGroup(status.group() > 0 ? -1 : status.preferredGroup());
    }
  }

  private void submit() {
    var status = menu.status();
    if (status == null || !status.editable() || status.group() < 0) {
      return;
    }
    try {
      int value = Integer.parseInt(input.getValue());
      if (value > 0 && value != status.group()) {
        menu.requestGroup(value);
      }
    } catch (NumberFormatException ignored) {
      input.setValue(Integer.toString(status.preferredGroup()));
    }
  }

  @Override
  public boolean keyPressed(int key, int scanCode, int modifiers) {
    if (input.isFocused() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
      submit();
      input.setFocused(false);
      return true;
    }
    if (input.isFocused() && key != GLFW.GLFW_KEY_ESCAPE) {
      return input.keyPressed(key, scanCode, modifiers);
    }

    return super.keyPressed(key, scanCode, modifiers);
  }

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    if (button == 0 && input.isFocused() && !input.isMouseOver(mouseX, mouseY) && !toggle.isMouseOver(mouseX, mouseY)) {
      submit();
    }

    return super.mouseClicked(mouseX, mouseY, button);
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
    graphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, 256, 256);
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    graphics.drawString(font, Component.translatable("menu.mmcece.group.title"), 4, 4, 0xFFFFFF, true);
    graphics.drawString(font, Component.translatable("menu.mmcece.group.tooltip"), 6, 24, 0xFFFFFF, true);
    graphics.drawString(font, Component.translatable("menu.mmcece.group.value"), 6, 49, 0xFFFFFF, true);
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    super.render(graphics, mouseX, mouseY, partialTick);
    renderTooltip(graphics, mouseX, mouseY);
  }
}
