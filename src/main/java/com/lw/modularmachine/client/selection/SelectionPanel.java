package com.lw.modularmachine.client.selection;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** Reuses the legacy 86 by 32 factory card without stretching its border. */
final class SelectionPanel {
  private static final ResourceLocation TEXTURE = ResourceLocation
      .fromNamespaceAndPath("mmcece", "textures/gui/guifactoryelements.png");

  private SelectionPanel() {
  }

  static void draw(GuiGraphics graphics, int x, int y, int width, int height) {
    patch(graphics, x + 1, y + 1, width - 2, height - 2, 1, 1, 84, 30);
    patch(graphics, x + 1, y, width - 2, 1, 1, 0, 84, 1);
    patch(graphics, x + 1, y + height - 1, width - 2, 1, 1, 31, 84, 1);
    patch(graphics, x, y + 1, 1, height - 2, 0, 1, 1, 30);
    patch(graphics, x + width - 1, y + 1, 1, height - 2, 85, 1, 1, 30);
    patch(graphics, x, y, 1, 1, 0, 0, 1, 1);
    patch(graphics, x + width - 1, y, 1, 1, 85, 0, 1, 1);
    patch(graphics, x, y + height - 1, 1, 1, 0, 31, 1, 1);
    patch(graphics, x + width - 1, y + height - 1, 1, 1, 85, 31, 1, 1);
  }

  private static void patch(GuiGraphics graphics, int x, int y, int width, int height, int u, int v, int sourceWidth, int sourceHeight) {
    if (width <= 0 || height <= 0) {
      return;
    }
    graphics.pose().pushPose();
    try {
      graphics.pose().translate(x, y, 0);
      graphics.pose().scale((float) width / sourceWidth, (float) height / sourceHeight, 1);
      graphics.blit(TEXTURE, 0, 0, u, v, sourceWidth, sourceHeight, 256, 256);
    } finally {
      graphics.pose().popPose();
    }
  }
}
