package com.lw.modularmachine.client;

import com.lw.modularmachine.api.client.gui.DraggablePanel;
import com.lw.modularmachine.machine.network.NetworkPortResource;
import com.lw.modularmachine.util.client.gui.GuiTextures;
import com.lw.modularmachine.util.client.gui.PanelDragHandle;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Client-side choice of one exact resource from a dragged ingredient. */
final class NetworkPortGhostPicker implements DraggablePanel {
  private static final GuiTextures.NineSlice BACKGROUND = new GuiTextures.NineSlice(
      ResourceLocation.fromNamespaceAndPath("ae2", "textures/guis/background.png"),
      0,
      0,
      256,
      256,
      256,
      256,
      4);
  private static final int CELL = 18;
  private static final int PADDING = 4;
  private static final int FOOTER = 16;
  private static final Component TITLE = Component.translatable("menu.mmcece.network.choose_resource");
  private final Font font = Minecraft.getInstance().font;
  private final NetworkPortScreen parent;
  private final int slot;
  private final List<NetworkPortResource> candidates;
  private final UUID session;
  private final long revision;
  private int left;
  private int top;
  private final PanelDragHandle handle = PanelDragHandle.topRight(this, PADDING, PADDING);
  private final int columns;
  private final int rows;
  private final int panelWidth;
  private final int panelHeight;
  private final int lastPage;
  private int page;
  private int pressedButton = -1;
  private boolean dismissOnRelease;
  private @Nullable NetworkPortResource pressedChoice;

  NetworkPortGhostPicker(NetworkPortScreen parent, int slot, List<NetworkPortResource> candidates, UUID session, long revision) {
    this.parent = parent;
    this.slot = slot;
    this.candidates = List.copyOf(candidates);
    this.session = session;
    this.revision = revision;
    var anchor = parent.ghostArea(slot);
    int minX = Math.max(0, parent.getGuiLeft());
    int maxX = Math.min(parent.width, parent.getGuiLeft() + parent.getXSize());
    int minY = Math.max(0, parent.getGuiTop());
    int maxY = Math.min(parent.height, parent.getGuiTop() + parent.getYSize());
    int below = anchor.getY() + anchor.getHeight() + 1;
    int above = anchor.getY() - 1;
    int room = Math.max(maxY - below, above - minY);
    int header = headerHeight();
    columns = Math.clamp(candidates.size(), 1, Math.max(1, (maxX - minX - 2 * PADDING) / CELL));
    int totalRows = Math.max(1, Math.ceilDiv(candidates.size(), columns));
    int gridRoom = room - header - PADDING;
    int availableRows = Math.max(1, gridRoom / CELL);
    boolean paged = totalRows > availableRows;
    rows = paged ? Math.max(1, (gridRoom - FOOTER) / CELL) : totalRows;
    lastPage = (totalRows - 1) / rows;
    int titleWidth = PADDING + font.width(TITLE) + handle.reservedWidth();
    panelWidth = Math.min(maxX - minX, Math.max(columns * CELL + 2 * PADDING, titleWidth));
    panelHeight = header + rows * CELL + PADDING + (paged ? FOOTER : 0);
    left = Math.clamp(anchor.getX() - PADDING - 1, minX, Math.max(minX, maxX - panelWidth));
    top = Math
        .clamp(maxY - below >= panelHeight ? below : above - panelHeight, minY, Math.max(minY, maxY - panelHeight));
  }

  @Override
  public ScreenRectangle bounds() {
    return new ScreenRectangle(left, top, panelWidth, panelHeight);
  }

  @Override
  public void setPosition(int left, int top) {
    this.left = left;
    this.top = top;
  }

  private int gridTop() {
    return top + headerHeight();
  }

  private int headerHeight() {
    return Math.max(handle.reservedHeight(), font.lineHeight + 2 * PADDING);
  }

  void render(GuiGraphics graphics, int mouseX, int mouseY) {
    int gridTop = gridTop();
    graphics.pose().pushPose();
    graphics.pose().translate(0, 0, 350);
    GuiTextures.blitNineSlice(graphics, BACKGROUND, left, top, panelWidth, panelHeight);
    graphics.drawString(
        font,
        font.plainSubstrByWidth(TITLE.getString(), Math.max(0, panelWidth - PADDING - handle.reservedWidth())),
        left + PADDING,
        top + (headerHeight() - font.lineHeight) / 2,
        0xFF404040,
        false);
    int first = page * rows * columns;
    int visible = Math.min(rows * columns, candidates.size() - first);
    for (int cell = 0; cell < visible; cell++) {
      int x = left + PADDING + cell % columns * CELL;
      int y = gridTop + cell / columns * CELL;
      NetworkPortAmountWidgets.slotBackground(graphics, x, y);
      parent.drawResource(graphics, candidates.get(first + cell), 1, x + 1, y + 1, false);
    }
    var hovered = choiceAt(mouseX, mouseY);
    if (hovered != null) {
      int x = mouseX - (mouseX - left - PADDING) % CELL + 1;
      int y = mouseY - (mouseY - gridTop) % CELL + 1;
      graphics.fill(x, y, x + 16, y + 16, 200, 0x80FFFFFF);
    }
    if (lastPage > 0) {
      int y = top + panelHeight - PADDING - FOOTER;
      drawPageButton(graphics, left + PADDING, y, "<", page > 0, mouseX, mouseY);
      drawPageButton(graphics, left + panelWidth - PADDING - FOOTER, y, ">", page < lastPage, mouseX, mouseY);
      String label = (page + 1) + "/" + (lastPage + 1);
      if (font.width(label) <= panelWidth - 2 * (PADDING + FOOTER)) {
        graphics.drawString(font, label, left + (panelWidth - font.width(label)) / 2, y + 4, 0xFF404040, false);
      }
    }
    if (hovered instanceof NetworkPortResource.Item item) {
      graphics.renderTooltip(font, item.stack(), mouseX, mouseY);
    } else if (hovered != null) {
      graphics.renderTooltip(font, NetworkPortScreen.resourceName(hovered), mouseX, mouseY);
    }
    handle.render(graphics, mouseX, mouseY, 0);
    graphics.pose().popPose();
  }

  private void drawPageButton(GuiGraphics graphics, int x, int y, String arrow, boolean enabled, int mouseX, int mouseY) {
    boolean hovered = enabled && mouseX >= x && mouseX < x + FOOTER && mouseY >= y && mouseY < y + FOOTER;
    graphics.renderOutline(x, y, FOOTER, FOOTER, hovered ? 0xFFFFFFFF : 0xFF888888);
    graphics
        .drawString(font, arrow, x + (FOOTER - font.width(arrow)) / 2, y + 4, enabled ? 0xFF404040 : 0xFF888888, false);
  }

  @Nullable
  private NetworkPortResource choiceAt(double x, double y) {
    double gridX = x - left - PADDING;
    double gridY = y - gridTop();
    if (gridX < 0 || gridX >= columns * CELL || gridY < 0 || gridY >= rows * CELL) {
      return null;
    }
    int cell = (int) gridY / CELL * columns + (int) gridX / CELL;
    long index = (long) page * rows * columns + cell;
    return index < candidates.size() ? candidates.get((int) index) : null;
  }

  boolean contains(double x, double y) {
    return x >= left && x < left + panelWidth && y >= top && y < top + panelHeight;
  }

  private boolean changePageAt(double x, double y) {
    if (lastPage == 0 || y < top + panelHeight - PADDING - FOOTER || y >= top + panelHeight - PADDING) {
      return false;
    }
    if (x >= left + PADDING && x < left + PADDING + FOOTER) {
      page = Math.max(0, page - 1);
      return true;
    }
    if (x >= left + panelWidth - PADDING - FOOTER && x < left + panelWidth - PADDING) {
      page = Math.min(lastPage, page + 1);
      return true;
    }
    return false;
  }

  boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
    if (!handle.isHeld() && contains(x, y) && vertical != 0) {
      page = Math.clamp(page - (int) Math.signum(vertical), 0, lastPage);
      pressedChoice = null;
    }
    return true;
  }

  boolean mouseClicked(double x, double y, int button) {
    if (pressedButton != -1) {
      return true;
    }
    pressedButton = button;
    dismissOnRelease = !contains(x, y);
    if (handle.mouseClicked(x, y, button)) {
      return true;
    }
    pressedChoice = button == 0 && !dismissOnRelease && !changePageAt(x, y) ? choiceAt(x, y) : null;
    return true;
  }

  boolean mouseDragged(double x, double y, int button, double dx, double dy) {
    handle.mouseDragged(x, y, button, dx, dy);
    return true;
  }

  boolean mouseReleased(double x, double y, int button) {
    if (button == pressedButton) {
      var choice = pressedChoice;
      pressedButton = -1;
      pressedChoice = null;
      if (handle.mouseReleased(x, y, button)) {
        return true;
      }
      if (dismissOnRelease || !current()) {
        parent.closeGhostPicker();
      } else if (choice != null && choice == choiceAt(x, y)) {
        parent.closeGhostPicker();
        parent.acceptGhost(slot, choice, session, revision);
      }
    }
    return true;
  }

  boolean current() {
    return parent.isGhostEditCurrent(session, revision);
  }
}
