package com.lw.modularmachine.util.client.gui;

import com.lw.modularmachine.api.client.gui.DraggablePanel;
import java.util.List;
import net.minecraft.client.gui.components.events.AbstractContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelDragHandleTest {
  private final Panel panel = new Panel();
  private long now = 1000;
  private final PanelDragHandle handle = new PanelDragHandle(
      panel,
      7,
      11,
      false,
      () -> now,
      () -> new ScreenRectangle(0, 0, 200, 150));

  @Test
  void pressesOnlyInsideThePanelRelativeGrip() {
    assertFalse(handle.mouseClicked(12.99, 31, 0));
    assertFalse(handle.mouseClicked(17, 26.99, 0));
    assertFalse(handle.mouseClicked(37, 31, 0));
    assertFalse(handle.mouseClicked(17, 51, 0));
    assertFalse(handle.mouseClicked(17, 31, 1));
    assertFalse(handle.isHeld());
    assertTrue(handle.mouseClicked(13, 27, 0));
    assertTrue(handle.mouseReleased(13, 27, 0));
    assertTrue(handle.mouseClicked(36.99, 50.99, 0));
  }

  @Test
  void topRightGripIncludesTheCornerAndStopsAtTheMaterialRow() {
    var bounds = panel.bounds();
    var grip = PanelDragHandle.topRight(panel, 4, 4);
    assertTrue(grip.isMouseOver(bounds.right() - 0.01, bounds.top()));
    assertTrue(grip.isMouseOver(bounds.right() - 24, bounds.top() + 23.99));
    assertFalse(grip.isMouseOver(bounds.right() - 25, bounds.top()));
    assertFalse(grip.isMouseOver(bounds.right(), bounds.top()));
    assertFalse(grip.isMouseOver(bounds.right() - 1, bounds.top() + 24));

    panel.width += 32;
    assertFalse(grip.isMouseOver(bounds.right() - 1, bounds.top()));
    assertTrue(grip.isMouseOver(panel.bounds().right() - 1, bounds.top()));
  }

  @Test
  void startsMovingAtThreeHundredMillisecondsAndStopsOnRelease() {
    assertTrue(handle.mouseClicked(17, 31, 0));
    now = 1299;
    handle.mouseDragged(22, 35, 0, 5, 4);
    assertEquals(new ScreenRectangle(10, 20, 80, 60), panel.bounds());
    now = 1300;
    handle.mouseDragged(22, 35, 0, 0, 0);
    assertEquals(new ScreenRectangle(15, 24, 80, 60), panel.bounds());

    assertTrue(handle.mouseReleased(27, 40, 0));
    assertFalse(handle.isHeld());
    assertEquals(new ScreenRectangle(20, 29, 80, 60), panel.bounds());
    assertFalse(handle.mouseDragged(60, 75, 0, 33, 35));
    assertEquals(new ScreenRectangle(20, 29, 80, 60), panel.bounds());
  }

  @Test
  void draggingTwiceThroughScreenEventsKeepsTheSecondPress() {
    var screen = new AbstractContainerEventHandler() {
      @Override
      public List<? extends GuiEventListener> children() {
        return List.of(handle);
      }
    };
    assertTrue(screen.mouseClicked(17, 31, 0));
    now += 300;
    assertTrue(screen.mouseReleased(27, 41, 0));
    assertTrue(screen.mouseClicked(27, 41, 0));
    now += 300;
    assertTrue(screen.mouseDragged(37, 51, 0, 10, 10));
    assertTrue(screen.mouseReleased(38, 52, 0));
    assertEquals(new ScreenRectangle(31, 41, 80, 60), panel.bounds());
  }

  @Test
  void otherButtonsCannotMoveOrReleaseAnActiveGrip() {
    assertTrue(handle.mouseClicked(17, 31, 0));
    now = 1400;
    assertFalse(handle.mouseClicked(19, 32, 1));
    assertFalse(handle.mouseDragged(100, 75, 1, 83, 44));
    assertFalse(handle.mouseReleased(100, 75, 1));
    assertTrue(handle.isHeld());
    assertEquals(new ScreenRectangle(10, 20, 80, 60), panel.bounds());
    assertTrue(handle.mouseReleased(150, 120, 0));
    assertEquals(new ScreenRectangle(120, 90, 80, 60), panel.bounds());
  }

  @Test
  void leavingBeforeTheHoldCompletesCancelsTheGesture() {
    assertTrue(handle.mouseClicked(17, 31, 0));
    now = 1299;
    handle.mouseDragged(37, 31, 0, 20, 0);
    assertFalse(handle.isHeld());
    now = 1300;
    handle.mouseDragged(22, 35, 0, -15, 4);
    assertEquals(new ScreenRectangle(10, 20, 80, 60), panel.bounds());
  }

  private static final class Panel implements DraggablePanel {
    private int left = 10;
    private int top = 20;
    private int width = 80;

    @Override
    public ScreenRectangle bounds() {
      return new ScreenRectangle(left, top, width, 60);
    }

    @Override
    public void setPosition(int left, int top) {
      this.left = left;
      this.top = top;
    }
  }
}
