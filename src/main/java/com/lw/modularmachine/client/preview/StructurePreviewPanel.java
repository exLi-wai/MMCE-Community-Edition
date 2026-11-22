package com.lw.modularmachine.client.preview;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal frame shared by the JEI structure details and ranged tool editor.
 */
@ApiStatus.Internal
public record StructurePreviewPanel(int left, int top, int width, int height) {
  public static StructurePreviewPanel forScreen(int screenWidth, int screenHeight) {
    int left = Math.max(12, (screenWidth - Math.min(760, screenWidth - 24)) / 2);
    int top = Math.max(12, (screenHeight - Math.min(900, screenHeight - 24)) / 2);

    return new StructurePreviewPanel(
        left,
        top,
        Math.min(760, screenWidth - left * 2),
        Math.min(900, screenHeight - top * 2));
  }

  public void drawBackground(GuiGraphics graphics) {
    graphics.fill(left, top, left + width, top + height, 0xFFC6C6C6);
    graphics.renderOutline(left, top, width, height, 0xFF555555);
  }

  ScreenRectangle settingsButton() {
    return new ScreenRectangle(left + width - 30, top + 10, 20, 22);
  }

  ScreenRectangle modifierIcon() {
    return new ScreenRectangle(settingsButton().left() - 20, top + 13, 16, 16);
  }

  void drawHeader(GuiGraphics graphics, Font font, Component title, ItemStack icon, boolean hasModifier) {
    if (!icon.isEmpty()) {
      graphics.renderItem(icon, left + 18, top + 12);
    }
    int textX = left + 37;
    int end = hasModifier ? modifierIcon().left() : settingsButton().left();
    var visible = font.substrByWidth(title, Math.max(0, end - 4 - textX));
    graphics.drawString(font, Language.getInstance().getVisualOrder(visible), textX, top + 16, 0xFF202020, false);
  }
}
