package com.lw.modularmachine.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * AE resource artwork without linking the common port screen to AE menu
 * classes.
 */
final class NetworkPortAmountWidgets {
  private static final ResourceLocation ICONS = ResourceLocation
      .fromNamespaceAndPath("ae2", "textures/guis/states.png");
  private static final ResourceLocation NORMAL = ResourceLocation.fromNamespaceAndPath("ae2", "button");
  private static final ResourceLocation HIGHLIGHTED = ResourceLocation
      .fromNamespaceAndPath("ae2", "button_highlighted");
  private static final ResourceLocation DISABLED = ResourceLocation.fromNamespaceAndPath("ae2", "button_disabled");

  private NetworkPortAmountWidgets() {
  }

  static Button gear(int x, int y, Runnable action) {
    var button = new Button(
        x,
        y,
        16,
        16,
        Component.translatable("menu.mmcece.network.amount"),
        ignored -> action.run(),
        narration -> narration.get()) {
      @Override
      protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int u = active && isHoveredOrFocused() ? 32 : 48;
        graphics.blit(ICONS, getX(), getY(), u, 64, 16, 16, 256, 256);
      }
    };
    button.setTooltip(Tooltip.create(button.getMessage()));
    return button;
  }

  static Button button(int x, int y, int width, Component label, Runnable action) {
    return new Button(x, y, width, 20, label, ignored -> action.run(), narration -> narration.get()) {
      @Override
      protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        background(graphics, this);
        graphics.drawCenteredString(
            Minecraft.getInstance().font,
            getMessage(),
            getX() + getWidth() / 2,
            getY() + 6,
            active ? 0xE0E0E0 : 0xA0A0A0);
      }
    };
  }

  static Button back(int x, int y, Runnable action) {
    return new Button(
        x,
        y,
        20,
        20,
        Component.translatable("gui.back"),
        ignored -> action.run(),
        narration -> narration.get()) {
      @Override
      protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        background(graphics, this);
        graphics.blit(ICONS, getX() + 2, getY() + 2, 96, 16, 16, 16, 256, 256);
      }
    };
  }

  private static void background(GuiGraphics graphics, Button button) {
    ResourceLocation sprite = button.active ? button.isHoveredOrFocused() ? HIGHLIGHTED : NORMAL : DISABLED;
    graphics.blitSprite(sprite, button.getX(), button.getY(), button.getWidth(), button.getHeight());
  }
}
