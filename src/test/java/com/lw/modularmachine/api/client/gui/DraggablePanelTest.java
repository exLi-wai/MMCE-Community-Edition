package com.lw.modularmachine.api.client.gui;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DraggablePanelTest {
  @Test
  void preservesGrabOffsetAndRoundsToTheNearestPixel() {
    var panel = new Panel();
    var drag = panel.beginDrag(18.25, 24.75);

    drag.moveTo(18.25, 24.75, 100, 80);
    assertEquals(new ScreenRectangle(10, 20, 30, 15), panel.bounds());
    drag.moveTo(58.0, 42.0, 100, 80);
    assertEquals(new ScreenRectangle(50, 37, 30, 15), panel.bounds());
  }

  @Test
  void clampsAtEachEdgeAndRegrabsFromTheNewPosition() {
    var panel = new Panel();
    var drag = panel.beginDrag(15, 23);
    drag.moveTo(-8, -3, 100, 80);
    assertEquals(new ScreenRectangle(0, 0, 30, 15), panel.bounds());
    drag.moveTo(120, 90, 100, 80);
    assertEquals(new ScreenRectangle(70, 65, 30, 15), panel.bounds());

    var nextDrag = panel.beginDrag(92, 76);
    nextDrag.moveTo(62, 51, 100, 80);
    assertEquals(new ScreenRectangle(40, 40, 30, 15), panel.bounds());
  }

  private static final class Panel implements DraggablePanel {
    private ScreenRectangle bounds = new ScreenRectangle(10, 20, 30, 15);

    @Override
    public ScreenRectangle bounds() {
      return bounds;
    }

    @Override
    public void setPosition(int left, int top) {
      bounds = new ScreenRectangle(left, top, bounds.width(), bounds.height());
    }
  }
}
