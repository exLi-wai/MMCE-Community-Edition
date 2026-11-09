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
import java.util.NavigableMap;
import java.util.TreeMap;
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
  private long candidateScroll;
  private long lastCycle;
  private SceneStatistics statistics;
  private Integer shownY;
  private boolean pickReady;

  /**
   * Statistics belong to the complete scene, independently of material-list
   * inclusion.
   */
  private static final class SceneStatistics {
    final PreviewSceneData scene;
    final NavigableMap<Integer, Integer> layers = new TreeMap<>();
    int cursor, blocks;
    int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
    int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

    SceneStatistics(PreviewSceneData scene) {
      this.scene = scene;
    }

    boolean ready() {
      return cursor == scene.entries().size();
    }

    void advance(int budget, long deadline) {
      for (int work = 0; work < budget && !ready() && System.nanoTime() < deadline; work++) {
        var entry = scene.entries().get(cursor++);
        var pos = entry.position();
        minX = Math.min(minX, pos.getX());
        minY = Math.min(minY, pos.getY());
        minZ = Math.min(minZ, pos.getZ());
        maxX = Math.max(maxX, pos.getX());
        maxY = Math.max(maxY, pos.getY());
        maxZ = Math.max(maxZ, pos.getZ());
        boolean block = entry.states().stream().anyMatch(state -> !state.isAir());
        layers.merge(pos.getY(), block ? 1 : 0, Integer::sum);
        if (block) {
          blocks++;
        }
      }
    }

    long width() {
      return scene.entries().isEmpty() ? 0 : (long) maxX - minX + 1;
    }

    long height() {
      return scene.entries().isEmpty() ? 0 : (long) maxY - minY + 1;
    }

    long depth() {
      return scene.entries().isEmpty() ? 0 : (long) maxZ - minZ + 1;
    }
  }

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
      statistics = null;
      shownY = null;
      pickReady = false;
      selectPosition(-1);
      materialScroll = 0;
      renderer.close();
      init();
    }
    selection.advance(2048, System.nanoTime() + 2_000_000L);
    if (selection.scene() != scene) {
      scene = selection.scene();
      animated = scene.entries().stream().filter(entry -> entry.states().size() > 1).toList();
      renderer.setScene(scene);
      statistics = new SceneStatistics(scene);
      shownY = null;
      pickReady = false;
      selectPosition(-1);
    }
    if (statistics != null && !statistics.ready()) {
      statistics.advance(2048, System.nanoTime() + 2_000_000L);
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
    drawStatistics(graphics, left, top, panelWidth);
    if (scene != null) {
      drawScene(graphics, left, top, panelWidth, panelHeight);
    }
    drawSettingsButton(graphics, left, top, panelWidth, mouseX, mouseY);
    positionFields(materialPanel());
    if (settingsOpen) {
      drawRequiredBlocks(graphics, mouseX, mouseY, partialTick);
    }
    drawSelectedCandidates(graphics, mouseX, mouseY);
    drawLayerControls(graphics, mouseX, mouseY);
    if (mouseX >= left + 18 && mouseX < left + panelWidth - 18 && mouseY >= top + 30 && mouseY < top + 44) {
      graphics.renderTooltip(
          font,
          List.of(statisticsText(), tr("details.statistics_help")),
          Optional.empty(),
          mouseX,
          mouseY);
    }
  }

  private Component statisticsText() {
    if (statistics == null || !statistics.ready()) {
      return tr("details.statistics_pending");
    }
    if (shownY == null) {
      return tr("details.dimensions", statistics.width(), statistics.height(), statistics.depth(), statistics.blocks);
    }
    return tr(
        "details.dimensions_layer",
        statistics.width(),
        statistics.height(),
        statistics.depth(),
        statistics.blocks,
        statistics.layers.getOrDefault(shownY, 0));
  }

  private void drawStatistics(GuiGraphics graphics, int left, int top, int panelWidth) {
    graphics.drawString(
        font,
        font.plainSubstrByWidth(statisticsText().getString(), Math.max(0, panelWidth - 36)),
        left + 18,
        top + 34,
        0xFF404040,
        false);
  }

  private record LayerControls(int left, int top, int labelWidth) {
    int labelX() {
      return left + 26;
    }

    int nextX() {
      return labelX() + labelWidth + 4;
    }

    int allX() {
      return nextX() + 26;
    }

    boolean contains(double x, double y) {
      return x >= left && x < allX() + 52 && y >= top && y < top + 18;
    }
  }

  private LayerControls layerControls() {
    int left = Math.max(12, (width - Math.min(760, width - 24)) / 2);
    int top = Math.max(12, (height - Math.min(900, height - 24)) / 2);
    int panelWidth = Math.min(760, width - left * 2);
    int panelHeight = Math.min(900, height - top * 2);
    int labelWidth = Math.max(40, Math.min(116, panelWidth - 132));
    int controlsWidth = labelWidth + 108;
    return new LayerControls(left + (panelWidth - controlsWidth) / 2, top + panelHeight - 28, labelWidth);
  }

  private Integer adjacentLayer(boolean next) {
    if (statistics == null || !statistics.ready() || statistics.layers.isEmpty()) {
      return null;
    }
    if (shownY == null) {
      return next ? statistics.layers.firstKey() : statistics.layers.lastKey();
    }
    return next ? statistics.layers.higherKey(shownY) : statistics.layers.lowerKey(shownY);
  }

  private void showLayer(Integer layer) {
    shownY = layer;
    pickReady = false;
    dragging = false;
    selectPosition(-1);
  }

  private void moveLayer(boolean next) {
    var layer = adjacentLayer(next);
    if (layer != null) {
      showLayer(layer);
    }
  }

  private void drawLayerControls(GuiGraphics graphics, int mouseX, int mouseY) {
    var controls = layerControls();
    drawStepButton(graphics, controls.left(), controls.top(), "<", adjacentLayer(false) != null, mouseX, mouseY);
    drawStepButton(graphics, controls.nextX(), controls.top(), ">", adjacentLayer(true) != null, mouseX, mouseY);
    var label = shownY == null ? tr("details.layer_all") : tr("details.layer_current", shownY);
    GuiTextures.blitNineSlice(graphics, CONTROL_TEXTURE, controls.labelX(), controls.top(), controls.labelWidth(), 18);
    String visible = font.plainSubstrByWidth(label.getString(), controls.labelWidth() - 8);
    graphics.drawString(
        font,
        visible,
        controls.labelX() + (controls.labelWidth() - font.width(visible)) / 2,
        controls.top() + 5,
        0xFF404040,
        false);
    GuiTextures.blitNineSlice(graphics, CONTROL_TEXTURE, controls.allX(), controls.top(), 52, 18);
    var all = tr("details.layer_show_all");
    graphics.drawString(
        font,
        all,
        controls.allX() + (52 - font.width(all)) / 2,
        controls.top() + 5,
        shownY == null ? 0xFF888888 : 0xFF202020,
        false);
    if (controls.contains(mouseX, mouseY)) {
      var action = mouseX < controls.labelX() ? tr("details.layer_previous") : mouseX >= controls.allX() ? tr(
          "details.layer_show_all") : mouseX >= controls.nextX() ? tr("details.layer_next") : label;
      graphics.renderTooltip(font, List.of(action, tr("details.layer_help")), Optional.empty(), mouseX, mouseY);
    }
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
    int panelHeight = Math.max(74, Math.min(parentHeight - 92, contentHeight(panelWidth) + 42));
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
        shownY == null ? PreviewRenderer.ALL_LAYERS : shownY,
        step);
    pickReady = true;
  }

  private record CandidatePanel(int left, int top, int width, int height, int columns, int count) {
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

  private CandidatePanel candidatePanel() {
    if (selected < 0 || selected >= selection.parts().size()) {
      return null;
    }
    int count = selection.parts().get(selected).alternatives().size();
    if (count == 0) {
      return null;
    }
    int parentLeft = Math.max(12, (width - Math.min(760, width - 24)) / 2);
    int parentTop = Math.max(12, (height - Math.min(900, height - 24)) / 2);
    int parentWidth = Math.min(760, width - parentLeft * 2);
    int parentHeight = Math.min(900, height - parentTop * 2);
    int availableWidth = Math.max(36, Math.min(CANDIDATE_MAX_WIDTH, parentWidth - 36));
    int columns = Math.max(1, Math.min(CANDIDATE_COLUMNS, (availableWidth - 16) / CANDIDATE_HORIZONTAL_SPACING));
    int panelWidth = Math.min(availableWidth, 16 + columns * CANDIDATE_HORIZONTAL_SPACING);
    long rows = (long) (count - 1) / columns + 1;
    int panelHeight = (int) Math.min(Math.max(38, parentHeight - 92), 38L + rows * CANDIDATE_VERTICAL_SPACING);

    return new CandidatePanel(parentLeft + 18, parentTop + 58, panelWidth, panelHeight, columns, count);
  }

  private void drawSelectedCandidates(GuiGraphics graphics, int mouseX, int mouseY) {
    var panel = candidatePanel();
    if (panel == null) {
      return;
    }
    var alternatives = selection.parts().get(selected).alternatives();
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

  private void selectPosition(int index) {
    if (index >= 0
        && (scene == null
            || index >= scene.entries().size()
            || shownY != null && scene.entries().get(index).position().getY() != shownY)) {
      index = -1;
    }
    if (selected != index) {
      selected = index;
      candidateScroll = 0;
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
    var layers = layerControls();
    if (layers.contains(x, y)) {
      setFocused(null);
      if (button == 0) {
        if (x < layers.left() + 22) {
          moveLayer(false);
        } else if (x >= layers.nextX() && x < layers.nextX() + 22) {
          moveLayer(true);
        } else if (x >= layers.allX()) {
          showLayer(null);
        }
      }
      return true;
    }
    var candidates = candidatePanel();
    if (candidates != null && candidates.contains(x, y)) {
      setFocused(null);
      if (button == 0 && candidates.closeContains(x, y)) {
        selectPosition(-1);
      }
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
    int left = Math.max(12, (width - Math.min(760, width - 24)) / 2);
    int top = Math.max(12, (height - Math.min(900, height - 24)) / 2);
    int panelHeight = Math.min(900, height - top * 2);
    if (x >= left + 8 && x < left + panelWidth - 8 && y >= top + 48 && y < top + panelHeight - 42) {
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
      if (shownY == null) {
        camera.rotate(dx, dy);
      } else {
        camera.pan(dx, dy);
      }
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
    if (dragging && button == 0 && dragDistance <= 4 && scene != null && pickReady) {
      selectPosition(renderer.pick(x, y));
    }
    dragging = false;
    return true;
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
      selectPosition(-1);
      return true;
    }
    return super.keyPressed(keyCode, scanCode, modifiers);
  }

  @Override
  public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
    if (layerControls().contains(x, y)) {
      if (scrollY != 0) {
        moveLayer(scrollY < 0);
      }
      return true;
    }
    var candidates = candidatePanel();
    if (candidates != null && candidates.contains(x, y)) {
      candidateScroll = Math.round(
          Math.max(0D, Math.min(candidates.maximumScroll(), candidateScroll - scrollY * CANDIDATE_VERTICAL_SPACING)));
      return true;
    }
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
