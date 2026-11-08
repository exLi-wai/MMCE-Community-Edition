package com.lw.modularmachine.integration.jei;

import com.lw.modularmachine.api.client.preview.PreviewCamera;
import com.lw.modularmachine.api.client.preview.PreviewRenderer;
import com.lw.modularmachine.api.client.preview.PreviewSceneData;
import com.lw.modularmachine.api.client.preview.PreviewViewport;
import com.lw.modularmachine.client.preview.MachinePreviewSelection;
import com.lw.modularmachine.util.client.gui.GuiTextures;
import com.lw.modularmachine.util.minecraft.registry.RegisteredMachineItems;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/** Full-screen structure preview opened by the JEI details button. */
public final class MachineDetailsScreen extends Screen {
  private static final int CANDIDATE_COLUMNS = 10;
  private static final int CANDIDATE_HORIZONTAL_SPACING = 20;
  private static final int CANDIDATE_VERTICAL_SPACING = 20;
  private static final int CANDIDATE_MAX_WIDTH = 250;
  private static final int REQUIRED_BLOCK_COLUMNS = 10;
  private static final int REQUIRED_BLOCK_HORIZONTAL_SPACING = 20;
  private static final int REQUIRED_BLOCK_VERTICAL_SPACING = 20;
  private static final int REQUIRED_BLOCK_MAX_WIDTH = 220;
  private static final int MAIN_CARD_HEIGHT = 46;
  private static final int DYNAMIC_CARD_HEIGHT = 74;
  private static final int CARD_GAP = 4;
  private static final int INCLUDE_BUTTON_WIDTH = 20;
  private static final int INCLUDE_BUTTON_HEIGHT = 22;
  private static final GuiTextures.NineSlice CONTROL_TEXTURE = new GuiTextures.NineSlice(
      ResourceLocation.fromNamespaceAndPath("mmcece", "textures/gui/button.png"),
      0,
      0,
      200,
      20,
      200,
      20,
      2);
  private static final ResourceLocation SETTINGS_BACKGROUND = ResourceLocation
      .fromNamespaceAndPath("mmcece", "textures/gui/button_2.png");
  private static final ResourceLocation SETTINGS_ICON = ResourceLocation
      .fromNamespaceAndPath("mmcece", "textures/gui/seetting.png");
  private ClientMachineJeiCatalog.Machine machine;
  private MachinePreviewSelection selection;
  private final List<EditBox> lengthFields = new ArrayList<>();
  private int materialScroll;
  private final PreviewRenderer renderer = PreviewRenderer.create();
  private final PreviewCamera camera = new PreviewCamera(89F);
  private PreviewSceneData scene;
  private List<PreviewSceneData.Entry> animated = List.of();
  private int step;
  private boolean cycle = true, dragging, settingsOpen = true;
  private double dragDistance;
  private int selected = -1;
  private long lastCycle;

  public MachineDetailsScreen(ClientMachineJeiCatalog.Machine machine) {
    super(Component.translatable("jei.mmcece.details.title", Component.translatable(machine.translation())));
    this.machine = machine;
  }

  @Override
  protected void init() {
    if (selection == null) {
      selection = new MachinePreviewSelection(machine.preview(), machine.color());
    }
    clearWidgets();
    lengthFields.clear();
    for (int i = 0; i < machine.preview().segments().size(); i++) {
      int index = i;
      var segment = machine.preview().segments().get(i);
      var field = new EditBox(font, 0, 0, 80, 18, tr("dynamic_layers", segment.id()));
      field.setMaxLength(10);
      field.setFilter(value -> value.matches("[0-9]*"));
      field.setValue(Integer.toString(selection.length(i)));
      field.setResponder(value -> {
        boolean valid = false;
        try {
          valid = selection.setLength(index, Integer.parseInt(value));
        } catch (NumberFormatException invalid) {
          // Keep the last complete layout while a number is being edited.
        }
        field.setTextColor(valid ? 0xFFE0E0E0 : 0xFFFF7777);
      });
      lengthFields.add(addWidget(field));
    }
    positionFields(materialPanel());
    lastCycle = Util.getMillis();
  }

  private static Component tr(String key, Object... args) {
    return Component.translatable("jei.mmcece." + key, args);
  }

  @Override
  public void tick() {
    if (minecraft.level == null) {
      onClose();
      return;
    }
    var current = ClientMachineJeiCatalog.machines().get(machine.id());
    if (current == null) {
      onClose();
      return;
    }
    if (current != machine) {
      machine = current;
      selection = null;
      scene = null;
      selected = -1;
      materialScroll = 0;
      renderer.close();
      init();
    }
    selection.advance(2048, System.nanoTime() + 2_000_000L);
    if (selection.scene() != scene) {
      scene = selection.scene();
      animated = scene.entries().stream().filter(entry -> entry.states().size() > 1).toList();
      renderer.setScene(scene);
      selected = -1;
    }
    if (cycle && !animated.isEmpty() && Util.getMillis() - lastCycle >= 1500) {
      step++;
      lastCycle = Util.getMillis();
    }
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    super.render(graphics, mouseX, mouseY, partialTick);
    int left = Math.max(12, (width - Math.min(760, width - 24)) / 2);
    int top = Math.max(12, (height - Math.min(900, height - 24)) / 2);
    int panelWidth = Math.min(760, width - left * 2);
    int panelHeight = Math.min(900, height - top * 2);
    graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xFFC6C6C6);
    graphics.renderOutline(left, top, panelWidth, panelHeight, 0xFF555555);
    drawHeader(graphics, left, top);
    if (scene != null) {
      drawScene(graphics, left, top, panelWidth, panelHeight);
    }
    drawSettingsButton(graphics, left, top, panelWidth, mouseX, mouseY);
    positionFields(materialPanel());
    if (settingsOpen) {
      drawRequiredBlocks(graphics, mouseX, mouseY, partialTick);
    }
    drawSelectedCandidates(graphics, left, top, panelWidth, panelHeight, mouseX, mouseY);
  }

  private void drawHeader(GuiGraphics graphics, int left, int top) {
    ItemStack icon = RegisteredMachineItems.controller(machine.id());
    if (icon.isEmpty()) {
      icon = RegisteredMachineItems.factory(machine.id());
    }
    if (!icon.isEmpty()) {
      graphics.renderItem(icon, left + 18, top + 12);
    }
    graphics.drawString(
        font,
        tr("details.title", Component.translatable(machine.translation())),
        left + 37,
        top + 16,
        0xFF202020,
        false);
  }

  private void drawSettingsButton(GuiGraphics graphics, int left, int top, int panelWidth, int mouseX, int mouseY) {
    int x = left + panelWidth - 30;
    int y = top + 10;
    boolean hovered = mouseX >= x && mouseX < x + 20 && mouseY >= y && mouseY < y + 22;
    graphics.blit(SETTINGS_BACKGROUND, x, y, 0, 0, 20, 22, 20, 22);
    graphics.blit(SETTINGS_ICON, x + 2, y + 3, 0, 0, 16, 16, 16, 16);
    if (hovered) {
      graphics.renderOutline(x, y, 20, 22, 0xFF205A70);
      graphics.renderTooltip(font, Component.translatable("jei.mmcece.required_blocks"), mouseX, mouseY);
    }
  }

  private record MaterialPanel(int left, int top, int width, int height) {
    int bodyTop() {
      return top + 24;
    }
    int bodyBottom() {
      return top + height - 18;
    }
    boolean contains(double x, double y) {
      return x >= left && x < left + width && y >= top && y < top + height;
    }
    boolean bodyContains(double x, double y) {
      return contains(x, y) && y >= bodyTop() && y < bodyBottom();
    }
  }

  private MaterialPanel materialPanel() {
    int left = Math.max(12, (width - Math.min(760, width - 24)) / 2);
    int top = Math.max(12, (height - Math.min(900, height - 24)) / 2);
    int parentWidth = Math.min(760, width - left * 2);
    int parentHeight = Math.min(900, height - top * 2);
    int panelWidth = Math.max(120, Math.min(REQUIRED_BLOCK_MAX_WIDTH, parentWidth - 36));
    int panelHeight = Math.max(74, Math.min(parentHeight - 76, contentHeight(panelWidth) + 42));
    return new MaterialPanel(left + parentWidth - panelWidth - 18, top + 58, panelWidth, panelHeight);
  }

  private int materialColumns(int panelWidth) {
    return Math.max(1, Math.min(REQUIRED_BLOCK_COLUMNS, (panelWidth - 16) / REQUIRED_BLOCK_HORIZONTAL_SPACING));
  }

  private int contentHeight(int panelWidth) {
    int columns = materialColumns(panelWidth);
    int rows = Math.max(1, (selection.materials().size() + columns - 1) / columns);
    return MAIN_CARD_HEIGHT + CARD_GAP + lengthFields
        .size() * (DYNAMIC_CARD_HEIGHT + CARD_GAP) + 18 + rows * REQUIRED_BLOCK_VERTICAL_SPACING + 4;
  }

  private int maximumScroll(MaterialPanel panel) {
    return Math.max(0, contentHeight(panel.width()) - (panel.bodyBottom() - panel.bodyTop()));
  }

  private void positionFields(MaterialPanel panel) {
    materialScroll = Math.clamp(materialScroll, 0, maximumScroll(panel));
    for (int i = 0; i < lengthFields.size(); i++) {
      var field = lengthFields.get(i);
      int y = panel
          .bodyTop() - materialScroll + MAIN_CARD_HEIGHT + CARD_GAP + i * (DYNAMIC_CARD_HEIGHT + CARD_GAP) + 17;
      field.setX(panel.left() + 40);
      field.setY(y);
      field.setWidth(panel.width() - 80);
      field.visible = settingsOpen && y >= panel.bodyTop() && y + 18 <= panel.bodyBottom();
      if (!field.visible && field.isFocused()) {
        setFocused(null);
      }
    }
  }

  private void drawRequiredBlocks(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    var panel = materialPanel();
    graphics.fill(panel.left(), panel.top(), panel.left() + panel.width(), panel.top() + panel.height(), 0xE6D5D5D5);
    graphics.renderOutline(panel.left(), panel.top(), panel.width(), panel.height(), 0xFF555555);
    graphics.drawString(font, tr("required_blocks"), panel.left() + 8, panel.top() + 8, 0xFF202020, false);
    graphics.drawString(font, "X", panel.left() + panel.width() - 16, panel.top() + 8, 0xFF202020, false);
    List<Component> tooltip = List.of();
    graphics.enableScissor(panel.left() + 2, panel.bodyTop(), panel.left() + panel.width() - 2, panel.bodyBottom());
    int firstY = panel.bodyTop() - materialScroll;
    drawCard(graphics, panel, firstY, MAIN_CARD_HEIGHT, tr("main_structure"), 0);
    for (int i = 0; i < lengthFields.size(); i++) {
      int y = firstY + MAIN_CARD_HEIGHT + CARD_GAP + i * (DYNAMIC_CARD_HEIGHT + CARD_GAP);
      if (y + DYNAMIC_CARD_HEIGHT <= panel.bodyTop() || y >= panel.bodyBottom()) {
        continue;
      }
      var segment = machine.preview().segments().get(i);
      drawCard(graphics, panel, y, DYNAMIC_CARD_HEIGHT, tr("dynamic_layers", segment.id()), i + 1);
      int amount = hasShiftDown() ? 4 : 1;
      drawStepButton(
          graphics,
          panel.left() + 14,
          y + 17,
          "-" + amount,
          selection.length(i) > segment.minimum(),
          mouseX,
          mouseY);
      drawStepButton(
          graphics,
          panel.left() + panel.width() - 36,
          y + 17,
          "+" + amount,
          selection.length(i) < segment.maximum(),
          mouseX,
          mouseY);
      lengthFields.get(i).render(graphics, mouseX, mouseY, partialTick);
      if (panel.bodyContains(mouseX, mouseY) && mouseY >= y && mouseY < y + 38) {
        tooltip = List.of(
            tr("dynamic_layers", segment.id()),
            tr("dynamic_range", segment.minimum(), segment.maximum()),
            tr("dynamic_step_help"));
      }
    }
    int materialsY = firstY + MAIN_CARD_HEIGHT + CARD_GAP + lengthFields.size() * (DYNAMIC_CARD_HEIGHT + CARD_GAP);
    graphics.drawString(font, tr("required_blocks"), panel.left() + 8, materialsY + 3, 0xFF202020, false);
    int columns = materialColumns(panel.width());
    int index = 0;
    for (var entry : selection.materials().entrySet()) {
      int x = panel.left() + 8 + index % columns * REQUIRED_BLOCK_HORIZONTAL_SPACING;
      int y = materialsY + 18 + index / columns * REQUIRED_BLOCK_VERTICAL_SPACING;
      index++;
      if (y + 18 <= panel.bodyTop() || y >= panel.bodyBottom()) {
        continue;
      }
      var stack = new ItemStack(entry.getKey(), entry.getValue());
      graphics.renderItem(stack, x, y);
      graphics.renderItemDecorations(font, stack, x, y);
      if (panel.bodyContains(mouseX, mouseY) && mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18) {
        tooltip = Screen.getTooltipFromItem(minecraft, stack);
      }
    }
    if (selection.materials().isEmpty()) {
      graphics.drawString(font, tr("materials_empty"), panel.left() + 8, materialsY + 20, 0xFF606060, false);
    }
    graphics.disableScissor();
    String status = selection
        .failed() ? "dynamic_invalid" : selection.building() ? "dynamic_building" : "materials_help";
    graphics.drawString(
        font,
        font.plainSubstrByWidth(tr(status).getString(), panel.width() - 12),
        panel.left() + 6,
        panel.top() + panel.height() - 12,
        selection.failed() ? 0xFFAA0000 : 0xFF606060,
        false);
    int maxScroll = maximumScroll(panel);
    if (maxScroll > 0) {
      int track = panel.bodyBottom() - panel.bodyTop();
      int thumb = Math.max(12, track * track / contentHeight(panel.width()));
      int y = panel.bodyTop() + (track - thumb) * materialScroll / maxScroll;
      graphics.fill(panel.left() + panel.width() - 4, y, panel.left() + panel.width() - 2, y + thumb, 0xFF707070);
    }
    if (!tooltip.isEmpty()) {
      graphics.renderTooltip(font, tooltip, Optional.empty(), mouseX, mouseY);
    }
  }

  private void drawCard(GuiGraphics graphics, MaterialPanel panel, int y, int height, Component title, int group) {
    int x = panel.left() + 8;
    int width = panel.width() - 16;
    graphics.fill(x, y, x + width, y + height, 0xFFC6C6C6);
    graphics.renderOutline(x, y, width, height, 0xFF555555);
    graphics.fill(x + 1, y + 1, x + width - 1, y + 3, selection.included(group) ? 0xFF52984A : 0xFF888888);
    graphics.drawString(font, font.plainSubstrByWidth(title.getString(), width - 12), x + 6, y + 6, 0xFF202020, false);
    int toggleY = y + height - INCLUDE_BUTTON_HEIGHT - 5;
    int textY = toggleY + (INCLUDE_BUTTON_HEIGHT - font.lineHeight) / 2;
    graphics.drawString(font, tr("include_materials"), x + 6, textY, 0xFF202020, false);
    int toggleX = panel.left() + panel.width() - 16 - INCLUDE_BUTTON_WIDTH;
    graphics.blit(
        SETTINGS_BACKGROUND,
        toggleX,
        toggleY,
        0,
        0,
        INCLUDE_BUTTON_WIDTH,
        INCLUDE_BUTTON_HEIGHT,
        INCLUDE_BUTTON_WIDTH,
        INCLUDE_BUTTON_HEIGHT);
    var text = tr(selection.included(group) ? "included_yes" : "included_no");
    graphics.drawString(
        font,
        text,
        toggleX + (INCLUDE_BUTTON_WIDTH - font.width(text)) / 2,
        textY,
        selection.included(group) ? 0xFF2F721B : 0xFF606060,
        false);
  }

  private void drawStepButton(GuiGraphics graphics, int x, int y, String text, boolean active, int mouseX, int mouseY) {
    GuiTextures.blitNineSlice(graphics, CONTROL_TEXTURE, x, y, 22, 18);
    if (active && mouseX >= x && mouseX < x + 22 && mouseY >= y && mouseY < y + 18) {
      graphics.renderOutline(x, y, 22, 18, 0xFF205A70);
    }
    graphics.drawString(font, text, x + (22 - font.width(text)) / 2, y + 5, active ? 0xFF202020 : 0xFF888888, false);
  }

  private void drawScene(GuiGraphics graphics, int panelLeft, int panelTop, int panelWidth, int panelHeight) {
    int left = panelLeft + 8, top = panelTop + 48, right = panelLeft + panelWidth - 8,
        bottom = panelTop + panelHeight - 42;
    float available = Math.min(right - left, bottom - top);
    float baseScale = Math.max(.01F, Math.min(24F, available * .55F / selection.extent()));
    renderer.render(
        graphics,
        new PreviewViewport(left, top, right - left, bottom - top, baseScale),
        camera,
        Integer.MIN_VALUE,
        step);
  }

  private void drawSelectedCandidates(GuiGraphics graphics, int panelLeft, int panelTop, int panelWidth, int panelHeight, int mouseX, int mouseY) {
    if (selected < 0 || selected >= selection.parts().size()) {
      return;
    }
    var alternatives = selection.parts().get(selected).alternatives();
    if (alternatives.isEmpty()) {
      return;
    }
    int left = candidateLeft(panelLeft);
    int top = candidateTop(panelTop);
    int columns = CANDIDATE_COLUMNS;
    int rows = (alternatives.size() + columns - 1) / columns;
    int width = Math.min(panelWidth - 36, Math.min(CANDIDATE_MAX_WIDTH, 16 + columns * CANDIDATE_HORIZONTAL_SPACING));
    int height = Math.min(panelHeight - 76, 38 + rows * CANDIDATE_VERTICAL_SPACING);
    graphics.fill(left, top, left + width, top + height, 0xE6D5D5D5);
    graphics.renderOutline(left, top, width, height, 0xFF555555);
    graphics.drawString(font, Component.translatable("jei.mmcece.candidates"), left + 8, top + 8, 0xFF202020, false);
    graphics.drawString(font, "X", left + width - 16, top + 8, 0xFF202020, false);
    for (int index = 0; index < alternatives.size(); index++) {
      var stack = new ItemStack(alternatives.get(index).getBlock());
      int x = left + 8 + index % columns * CANDIDATE_HORIZONTAL_SPACING;
      int y = top + 24 + index / columns * CANDIDATE_VERTICAL_SPACING;
      graphics.renderItem(stack, x, y);
      graphics.renderItemDecorations(font, stack, x, y);
      if (mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18) {
        graphics.renderTooltip(font, stack, mouseX, mouseY);
      }
    }
  }

  @Override
  public boolean mouseClicked(double x, double y, int button) {
    int panelLeft = Math.max(12, (width - Math.min(760, width - 24)) / 2);
    int panelTop = Math.max(12, (height - Math.min(900, height - 24)) / 2);
    int panelWidth = Math.min(760, width - panelLeft * 2);
    int settingsX = panelLeft + panelWidth - 30;
    int settingsY = panelTop + 10;
    if (button == 0 && x >= settingsX && x < settingsX + 20 && y >= settingsY && y < settingsY + 22) {
      settingsOpen = !settingsOpen;
      positionFields(materialPanel());
      return true;
    }
    var panel = materialPanel();
    positionFields(panel);
    if (settingsOpen && panel.contains(x, y)) {
      if (button == 0 && x >= panel.left() + panel.width() - 24 && y < panel.top() + 24) {
        settingsOpen = false;
        positionFields(panel);
        return true;
      }
      if (panel.bodyContains(x, y)) {
        if (button == 0 && clickMaterialControl(panel, x, y)) {
          return true;
        }
        if (super.mouseClicked(x, y, button)) {
          return true;
        }
      }
      setFocused(null);
      return true;
    }
    setFocused(null);
    if (button == 0
        && selected >= 0
        && x >= candidateLeft(Math.max(12, (width - Math.min(760, width - 24)) / 2)) + 180
        && x < candidateLeft(Math.max(12, (width - Math.min(760, width - 24)) / 2)) + 220
        && y >= candidateTop(Math.max(12, (height - Math.min(900, height - 24)) / 2))
        && y < candidateTop(Math.max(12, (height - Math.min(900, height - 24)) / 2)) + 24) {
      selected = -1;
      return true;
    }
    if (button == 0 && selected >= 0 && candidateBounds(x, y)) {
      return true;
    }
    int left = Math.max(12, (width - Math.min(760, width - 24)) / 2);
    int top = Math.max(12, (height - Math.min(900, height - 24)) / 2);
    int panelHeight = Math.min(900, height - top * 2);
    if (y >= top + 48 && y < top + panelHeight - 20) {
      dragging = true;
      dragDistance = 0;
      return true;
    }
    return super.mouseClicked(x, y, button);
  }

  private boolean clickMaterialControl(MaterialPanel panel, double x, double y) {
    double localY = y - panel.bodyTop() + materialScroll;
    int group;
    int cardY;
    int cardHeight;
    if (localY >= 0 && localY < MAIN_CARD_HEIGHT) {
      group = 0;
      cardY = 0;
      cardHeight = MAIN_CARD_HEIGHT;
    } else {
      int segment = (int) (localY - MAIN_CARD_HEIGHT - CARD_GAP) / (DYNAMIC_CARD_HEIGHT + CARD_GAP);
      if (localY < MAIN_CARD_HEIGHT + CARD_GAP || segment >= lengthFields.size()) {
        return false;
      }
      group = segment + 1;
      cardY = MAIN_CARD_HEIGHT + CARD_GAP + segment * (DYNAMIC_CARD_HEIGHT + CARD_GAP);
      cardHeight = DYNAMIC_CARD_HEIGHT;
      if (localY >= cardY + 17 && localY < cardY + 35) {
        int direction = 0;
        if (x >= panel.left() + 14 && x < panel.left() + 36) {
          direction = -1;
        } else if (x >= panel.left() + panel.width() - 36 && x < panel.left() + panel.width() - 14) {
          direction = 1;
        }
        if (direction != 0) {
          var bounds = machine.preview().segments().get(segment);
          long value = selection.length(segment) + (long) direction * (hasShiftDown() ? 4 : 1);
          lengthFields.get(segment).setValue(Long.toString(Math.clamp(value, bounds.minimum(), bounds.maximum())));
          setFocused(null);
          return true;
        }
      }
    }
    if (localY >= cardY + cardHeight - INCLUDE_BUTTON_HEIGHT - 5
        && localY < cardY + cardHeight - 5
        && x >= panel.left() + panel.width() - 16 - INCLUDE_BUTTON_WIDTH
        && x < panel.left() + panel.width() - 16) {
      selection.toggleIncluded(group);
      setFocused(null);
      return true;
    }
    return false;
  }

  @Override
  public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
    if (!dragging) {
      return super.mouseDragged(x, y, button, dx, dy);
    }
    if (button == 0) {
      dragDistance += Math.abs(dx) + Math.abs(dy);
      camera.rotate(dx, dy);
    } else {
      camera.pan(dx, dy);
    }
    return true;
  }

  @Override
  public boolean mouseReleased(double x, double y, int button) {
    if (!dragging) {
      return super.mouseReleased(x, y, button);
    }
    if (dragging && button == 0 && dragDistance <= 4 && scene != null) {
      selected = renderer.pick(x, y);
    }
    dragging = false;
    return true;
  }

  private boolean candidateBounds(double x, double y) {
    int panelLeft = Math.max(12, (width - Math.min(760, width - 24)) / 2);
    int panelTop = Math.max(12, (height - Math.min(900, height - 24)) / 2);
    return x >= candidateLeft(panelLeft)
        && x < candidateLeft(panelLeft) + 212
        && y >= candidateTop(panelTop)
        && y < candidateTop(panelTop) + 500;
  }

  private static int candidateLeft(int panelLeft) {
    return panelLeft + 18;
  }

  private static int candidateTop(int panelTop) {
    return panelTop + 58;
  }

  @Override
  public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    if (getFocused() instanceof EditBox) {
      return super.keyPressed(keyCode, scanCode, modifiers);
    }
    if (keyCode == GLFW.GLFW_KEY_X && settingsOpen) {
      settingsOpen = false;
      positionFields(materialPanel());
      return true;
    }
    if (keyCode == GLFW.GLFW_KEY_X && selected >= 0) {
      selected = -1;
      return true;
    }
    return super.keyPressed(keyCode, scanCode, modifiers);
  }

  @Override
  public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
    var panel = materialPanel();
    if (settingsOpen && panel.contains(x, y)) {
      materialScroll = (int) Math.clamp(materialScroll - Math.round(scrollY * 20), 0, maximumScroll(panel));
      positionFields(panel);
      return true;
    }
    if (y < 70 || y >= height - 26) {
      return false;
    }
    camera.zoomBy(scrollY);
    return true;
  }

  @Override
  public void removed() {
    renderer.close();
    super.removed();
  }
}
