package com.lw.modularmachine.util.client.gui;

import com.lw.modularmachine.api.client.gui.DraggablePanel;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** A hold-to-drag wrench. Screens can register it with addRenderableWidget. */
public final class PanelDragHandle implements Renderable, GuiEventListener, NarratableEntry {
  private static final int SIZE = 16;
  private static final int HIT_PADDING = 4;
  private static final long HOLD_MILLIS = 300;
  private static final long FLASH_MILLIS = 150;
  private static final ResourceLocation SPRITE = ResourceLocation.fromNamespaceAndPath("mmcece", "drag_handle");
  private static final ResourceLocation PRESSED_SPRITE = ResourceLocation
      .fromNamespaceAndPath("mmcece", "drag_handle_pressed");
  private static final Component LABEL = Component.translatable("gui.mmcece.drag_handle");
  private final DraggablePanel panel;
  private final int offsetX;
  private final int offsetY;
  private final boolean fromRight;
  private final LongSupplier clock;
  private final Supplier<ScreenRectangle> viewport;
  private @Nullable DraggablePanel.Drag drag;
  private long readyAt;
  private long flashUntil;
  private @Nullable Boolean focused;
  private boolean hovered;

  /** Places the wrench at (x, y) from the panel's top-left corner. */
  public PanelDragHandle(DraggablePanel panel, int x, int y) {
    this(panel, x, y, false);
  }

  /** Places the wrench relative to the panel's current top-right corner. */
  public static PanelDragHandle topRight(DraggablePanel panel, int right, int top) {
    return new PanelDragHandle(panel, right, top, true);
  }

  private PanelDragHandle(DraggablePanel panel, int x, int y, boolean fromRight) {
    this(panel, x, y, fromRight, Util::getMillis, PanelDragHandle::windowBounds);
  }

  PanelDragHandle(DraggablePanel panel, int x, int y, boolean fromRight, LongSupplier clock, Supplier<ScreenRectangle> viewport) {
    this.panel = panel;
    this.offsetX = x;
    this.offsetY = y;
    this.fromRight = fromRight;
    this.clock = clock;
    this.viewport = viewport;
  }

  private static ScreenRectangle windowBounds() {
    var window = Minecraft.getInstance().getWindow();
    return new ScreenRectangle(0, 0, window.getGuiScaledWidth(), window.getGuiScaledHeight());
  }

  /** Space from the horizontal anchor edge through the padded hit area. */
  public int reservedWidth() {
    return offsetX + SIZE + HIT_PADDING;
  }

  /** Space from the top edge through the padded hit area. */
  public int reservedHeight() {
    return offsetY + SIZE + HIT_PADDING;
  }

  private int iconLeft(ScreenRectangle bounds) {
    return fromRight ? bounds.right() - offsetX - SIZE : bounds.left() + offsetX;
  }

  @Override
  public ScreenRectangle getRectangle() {
    var bounds = panel.bounds();
    int size = SIZE + 2 * HIT_PADDING;
    var hit = new ScreenRectangle(iconLeft(bounds) - HIT_PADDING, bounds.top() + offsetY - HIT_PADDING, size, size);
    var clipped = hit.intersection(bounds);
    return clipped == null ? ScreenRectangle.empty() : clipped;
  }

  @Override
  public boolean isMouseOver(double x, double y) {
    var hit = getRectangle();
    return x >= hit.left() && x < hit.right() && y >= hit.top() && y < hit.bottom();
  }

  @Override
  public boolean mouseClicked(double x, double y, int button) {
    if (button != 0 || !isMouseOver(x, y)) {
      return false;
    }
    long now = clock.getAsLong();
    drag = panel.beginDrag(x, y);
    readyAt = now + HOLD_MILLIS;
    flashUntil = now + FLASH_MILLIS;
    return true;
  }

  public boolean isHeld() {
    return drag != null;
  }

  private boolean move(double x, double y, int button) {
    if (button != 0 || drag == null) {
      return false;
    }
    if (clock.getAsLong() >= readyAt) {
      var area = viewport.get();
      drag.moveTo(x, y, area.width(), area.height());
    } else if (!isMouseOver(x, y)) {
      drag = null;
    }
    return true;
  }

  @Override
  public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
    return move(x, y, button);
  }

  @Override
  public boolean mouseReleased(double x, double y, int button) {
    if (!move(x, y, button)) {
      return false;
    }
    drag = null;
    return true;
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    // A screen briefly clears focus even when clicking the already focused widget.
    // Inline overlays dispatch input directly and never assign keyboard focus.
    if (Boolean.FALSE.equals(focused)) {
      drag = null;
    }
    var bounds = panel.bounds();
    boolean highlighted = isHeld() || clock.getAsLong() < flashUntil;
    graphics.blitSprite(highlighted ? PRESSED_SPRITE : SPRITE, iconLeft(bounds), bounds.top() + offsetY, SIZE, SIZE);
    hovered = isMouseOver(mouseX, mouseY);
    if (hovered && !isHeld()) {
      graphics.renderTooltip(Minecraft.getInstance().font, LABEL, mouseX, mouseY);
    }
  }

  @Override
  public void setFocused(boolean focused) {
    this.focused = focused;
  }

  @Override
  public boolean isFocused() {
    return Boolean.TRUE.equals(focused);
  }

  @Override
  public NarrationPriority narrationPriority() {
    if (isFocused()) {
      return NarrationPriority.FOCUSED;
    }
    return hovered ? NarrationPriority.HOVERED : NarrationPriority.NONE;
  }

  @Override
  public void updateNarration(NarrationElementOutput output) {
    output.add(NarratedElementType.TITLE, LABEL);
  }
}
