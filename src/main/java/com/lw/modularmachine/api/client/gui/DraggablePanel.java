package com.lw.modularmachine.api.client.gui;

import net.minecraft.client.gui.navigation.ScreenRectangle;

/** A movable client panel. All coordinates are in GUI-scaled screen pixels. */
public interface DraggablePanel {
  ScreenRectangle bounds();

  void setPosition(int left, int top);

  /**
   * Call on handle press and retain the gesture until release or cancellation.
   */
  default Drag beginDrag(double mouseX, double mouseY) {
    ScreenRectangle start = bounds();
    double offsetX = mouseX - start.left();
    double offsetY = mouseY - start.top();
    return (x, y, viewportWidth, viewportHeight) -> {
      ScreenRectangle panel = bounds();
      int maxLeft = Math.max(0, viewportWidth - panel.width());
      int maxTop = Math.max(0, viewportHeight - panel.height());
      setPosition(Math.clamp(Math.round(x - offsetX), 0, maxLeft), Math.clamp(Math.round(y - offsetY), 0, maxTop));
    };
  }

  @FunctionalInterface
  interface Drag {
    void moveTo(double mouseX, double mouseY, int viewportWidth, int viewportHeight);
  }
}
