package com.lw.modularmachine.client.preview;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import net.minecraft.client.gui.Font;

/**
 * Selected-block alternatives, popup layout and independent scroll position.
 */
@ApiStatus.Internal
final class PreviewCandidatePanel {
  private static final int CANDIDATE_COLUMNS = 10;
  private static final int CANDIDATE_HORIZONTAL_SPACING = 20;
  private static final int CANDIDATE_VERTICAL_SPACING = 20;
  private static final int CANDIDATE_MAX_WIDTH = 250;
  private final PreviewDisplayState preview;
  private int selected = -1;
  private long candidateScroll;

  PreviewCandidatePanel(PreviewDisplayState preview) {
    this.preview = preview;
  }
  int selected() {
    return selected;
  }
  void scroll(Panel panel, double scrollY) {
    candidateScroll = Math
        .round(Math.max(0D, Math.min(panel.maximumScroll(), candidateScroll - scrollY * CANDIDATE_VERTICAL_SPACING)));
  }
  private static Component tr(String key) {
    return Component.translatable("jei.mmcece." + key);
  }

  record Panel(int left, int top, int width, int height, int columns, int count) {
    int bodyTop() {
      return top + 24;
    }
    int bodyBottom() {
      return top + height - 14;
    }
    long contentHeight() {
      return ((long) (count - 1) / columns + 1) * CANDIDATE_VERTICAL_SPACING;
    }
    long maximumScroll() {
      return Math.max(0L, contentHeight() - (bodyBottom() - bodyTop()));
    }
    boolean contains(double x, double y) {
      return x >= left && x < left + width && y >= top && y < top + height;
    }
    boolean bodyContains(double x, double y) {
      return contains(x, y) && y >= bodyTop() && y < bodyBottom();
    }
    boolean closeContains(double x, double y) {
      return contains(x, y) && x >= left + width - 24 && y < top + 24;
    }
  }

  Panel panel(StructurePreviewScreen.PreviewArea area) {
    if (preview.selection == null || selected < 0 || selected >= preview.selection.parts().size()) {
      return null;
    }
    int count = preview.selection.parts().get(selected).alternatives().size();
    if (count == 0) {
      return null;
    }
    if (area.width() < 56 || area.height() < 38) {
      return null;
    }
    int availableWidth = Math.max(36, Math.min(CANDIDATE_MAX_WIDTH, area.width() - 20));
    int columns = Math.max(1, Math.min(CANDIDATE_COLUMNS, (availableWidth - 16) / CANDIDATE_HORIZONTAL_SPACING));
    int panelWidth = Math.min(availableWidth, 16 + columns * CANDIDATE_HORIZONTAL_SPACING);
    long rows = (long) (count - 1) / columns + 1;
    int panelHeight = (int) Math.min(Math.max(38, area.height() - 2), 38L + rows * CANDIDATE_VERTICAL_SPACING);

    return new Panel(area.left() + 10, area.top() + 10, panelWidth, panelHeight, columns, count);
  }

  void draw(GuiGraphics graphics, Font font, StructurePreviewScreen.PreviewArea area, int mouseX, int mouseY) {
    var panel = panel(area);
    if (panel == null) {
      return;
    }
    var alternatives = preview.selection.parts().get(selected).alternatives();
    candidateScroll = Math.max(0L, Math.min(candidateScroll, panel.maximumScroll()));
    graphics.fill(panel.left(), panel.top(), panel.left() + panel.width(), panel.top() + panel.height(), 0xE6D5D5D5);
    graphics.renderOutline(panel.left(), panel.top(), panel.width(), panel.height(), 0xFF555555);
    graphics.drawString(
        font,
        font.plainSubstrByWidth(tr("candidates").getString(), panel.width() - 36),
        panel.left() + 8,
        panel.top() + 8,
        0xFF202020,
        false);
    graphics.drawString(font, "X", panel.left() + panel.width() - 16, panel.top() + 8, 0xFF202020, false);
    int visibleHeight = panel.bodyBottom() - panel.bodyTop();
    ItemStack hovered = ItemStack.EMPTY;
    if (visibleHeight > 0) {
      long firstRow = candidateScroll / CANDIDATE_VERTICAL_SPACING;
      long lastRow = (candidateScroll + visibleHeight + CANDIDATE_VERTICAL_SPACING - 1) / CANDIDATE_VERTICAL_SPACING;
      int first = (int) Math.min(panel.count(), firstRow * panel.columns());
      int end = (int) Math.min(panel.count(), lastRow * panel.columns());
      graphics.enableScissor(panel.left() + 2, panel.bodyTop(), panel.left() + panel.width() - 2, panel.bodyBottom());
      try {
        for (int index = first; index < end; index++) {
          var stack = new ItemStack(alternatives.get(index).getBlock());
          int x = panel.left() + 8 + index % panel.columns() * CANDIDATE_HORIZONTAL_SPACING;
          int y = panel
              .bodyTop() + (int) ((long) (index / panel.columns()) * CANDIDATE_VERTICAL_SPACING - candidateScroll);
          graphics.renderItem(stack, x, y);
          graphics.renderItemDecorations(font, stack, x, y);
          if (panel.bodyContains(mouseX, mouseY) && mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18) {
            hovered = stack;
          }
        }
      } finally {
        graphics.disableScissor();
      }
    }
    if (panel.maximumScroll() > 0 && visibleHeight > 0) {
      int thumb = (int) Math
          .min(visibleHeight, Math.max(12L, (long) visibleHeight * visibleHeight / panel.contentHeight()));
      int y = panel.bodyTop() + (int) ((visibleHeight - thumb) * ((double) candidateScroll / panel.maximumScroll()));
      graphics.fill(panel.left() + panel.width() - 4, y, panel.left() + panel.width() - 2, y + thumb, 0xFF707070);
    }
    if (!hovered.isEmpty()) {
      graphics.renderTooltip(font, hovered, mouseX, mouseY);
    }
  }

  void select(int index) {
    if (index >= 0
        && (preview.scene == null
            || index >= preview.scene.entries().size()
            || preview.shownY != null && preview.scene.entries().get(index).position().getY() != preview.shownY)) {
      index = -1;
    }
    if (selected != index) {
      selected = index;
      candidateScroll = 0;
    }
  }

}
