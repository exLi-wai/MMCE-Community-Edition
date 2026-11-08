package com.lw.modularmachine.client.selection;

import com.lw.modularmachine.util.client.gui.GuiTextures;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** Reuses the legacy 86 by 32 factory card without stretching its border. */
final class SelectionPanel {
  private static final GuiTextures.NineSlice TEXTURE = new GuiTextures.NineSlice(
      ResourceLocation.fromNamespaceAndPath("mmcece", "textures/gui/guifactoryelements.png"),
      0,
      0,
      86,
      32,
      256,
      256,
      1);

  private SelectionPanel() {
  }

  static void draw(GuiGraphics graphics, int x, int y, int width, int height) {
    GuiTextures.blitNineSlice(graphics, TEXTURE, x, y, width, height);
  }
}
