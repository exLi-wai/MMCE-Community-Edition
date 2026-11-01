package com.lw.modularmachine.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * Legacy scrollbar geometry with a bundled enabled/disabled thumb atlas.
 */
public final class LegacyGuiScrollbar {
  private static final ResourceLocation TEXTURE = ResourceLocation
      .fromNamespaceAndPath("mmcece", "textures/gui/legacy_scrollbar.png");
  private int x;
  private int y;
  private int width = 12;
  private int height = 16;
  private int pageSize = 1;
  private int minimum;
  private int maximum;
  private int current;

  public LegacyGuiScrollbar position(int x, int y) {
    this.x = x;
    this.y = y;
    return this;
  }

  public LegacyGuiScrollbar height(int height) {
    this.height = height;
    return this;
  }

  public LegacyGuiScrollbar range(int minimum, int maximum, int pageSize) {
    this.minimum = minimum;
    this.maximum = Math.max(minimum, maximum);
    this.pageSize = pageSize;
    clamp();
    return this;
  }

  public int current() {
    return current;
  }

  public void draw(GuiGraphics graphics) {
    int range = maximum - minimum;
    int textureU = range == 0 ? 12 : 0;
    int offset = range == 0 ? 0 : (current - minimum) * (height - 15) / range;
    graphics.blit(TEXTURE, x, y + offset, textureU, 0, width, 15, 24, 15);
  }

  public void wheel(int delta) {
    delta = Math.max(Math.min(-delta, 1), -1);
    current += delta * pageSize;
    clamp();
  }

  public void click(double mouseX, double mouseY) {
    if (maximum == minimum || !contains(mouseX, mouseY)) {
      return;
    }
    current = minimum + (int) (((mouseY - y) * 2 * (maximum - minimum) / height + 1) / 2);
    clamp();
  }

  private boolean contains(double mouseX, double mouseY) {
    return mouseX > x && mouseX <= x + width && mouseY > y && mouseY <= y + height;
  }

  private void clamp() {
    current = Math.max(Math.min(current, maximum), minimum);
  }
}
