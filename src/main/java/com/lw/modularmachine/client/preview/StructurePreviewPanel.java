package com.lw.modularmachine.client.preview;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
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

  public void drawHeader(GuiGraphics graphics, Font font, Component title, ItemStack icon) {
    if (!icon.isEmpty()) {
      graphics.renderItem(icon, left + 18, top + 12);
    }
    graphics.drawString(font, title, left + 37, top + 16, 0xFF202020, false);
  }
}
