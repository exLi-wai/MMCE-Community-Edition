package com.lw.modularmachine.util.client.gui;

import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal client-only texture drawing. No pose, shader or input state is
 * retained.
 */
@ApiStatus.Internal
public final class GuiTextures {
  /**
   * Pixel coordinates describe one region of a texture, including its fixed
   * border.
   */
  public record NineSlice(
      ResourceLocation texture,
      int u,
      int v,
      int width,
      int height,
      int textureWidth,
      int textureHeight,
      int border) {
    public NineSlice {
      Objects.requireNonNull(texture, "texture");
      if (u < 0
          || v < 0
          || width <= 0
          || height <= 0
          || textureWidth <= 0
          || textureHeight <= 0
          || u > textureWidth - width
          || v > textureHeight - height
          || border < 0
          || (long) border * 2 >= width
          || (long) border * 2 >= height) {
        throw new IllegalArgumentException("Invalid nine-slice texture region");
      }
    }
  }

  private GuiTextures() {
  }

  /**
   * Keeps border pixels unscaled and stretches the interior/edge strips. If the
   * destination is smaller than both borders, only the fitting outer pixels are
   * drawn.
   */
  public static void blitNineSlice(GuiGraphics graphics, NineSlice source, int x, int y, int width, int height) {
    if (width <= 0 || height <= 0) {
      return;
    }
    int border = source.border();
    int left = Math.min(border, width / 2);
    int right = Math.min(border, width - left);
    int top = Math.min(border, height / 2);
    int bottom = Math.min(border, height - top);
    for (int row = 0; row < 3; row++) {
      int sourceY = source.v() + (row == 0 ? 0 : row == 1 ? border : source.height() - bottom);
      int targetY = y + (row == 0 ? 0 : row == 1 ? top : height - bottom);
      int sourceHeight = row == 1 ? source.height() - 2 * border : row == 0 ? top : bottom;
      int targetHeight = row == 1 ? height - top - bottom : sourceHeight;
      for (int column = 0; column < 3; column++) {
        int sourceX = source.u() + (column == 0 ? 0 : column == 1 ? border : source.width() - right);
        int targetX = x + (column == 0 ? 0 : column == 1 ? left : width - right);
        int sourceWidth = column == 1 ? source.width() - 2 * border : column == 0 ? left : right;
        int targetWidth = column == 1 ? width - left - right : sourceWidth;
        if (targetWidth > 0 && targetHeight > 0) {
          graphics.blit(
              source.texture(),
              targetX,
              targetY,
              targetWidth,
              targetHeight,
              sourceX,
              sourceY,
              sourceWidth,
              sourceHeight,
              source.textureWidth(),
              source.textureHeight());
        }
      }
    }
  }
}
