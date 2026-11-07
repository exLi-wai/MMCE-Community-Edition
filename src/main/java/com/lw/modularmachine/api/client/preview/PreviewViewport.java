package com.lw.modularmachine.api.client.preview;

/**
 * Rectangle in the caller's GUI coordinates, with an unzoomed block scale and a
 * camera anchor in the same coordinates. The GUI pose may translate or scale
 * this rectangle; clipping is resolved by the renderer.
 */
public record PreviewViewport(int x, int y, int width, int height, float baseScale, float anchorX, float anchorY) {
  public PreviewViewport(int x, int y, int width, int height, float baseScale) {
    this(x, y, width, height, baseScale, x + width / 2F, y + height / 2F);
  }

  public PreviewViewport {
    if (width < 0
        || height < 0
        || !Float.isFinite(baseScale)
        || baseScale <= 0
        || !Float.isFinite(anchorX)
        || !Float.isFinite(anchorY)) {
      throw new IllegalArgumentException("Invalid preview viewport");
    }
  }

  public boolean contains(double mouseX, double mouseY) {
    return mouseX >= x && mouseX < (double) x + width && mouseY >= y && mouseY < (double) y + height;
  }
}
