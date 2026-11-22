package com.lw.modularmachine.client.preview;

import com.lw.modularmachine.api.client.preview.PreviewCamera;
import com.lw.modularmachine.api.client.preview.PreviewRenderer;
import com.lw.modularmachine.api.client.preview.PreviewSceneData;
import com.lw.modularmachine.api.client.preview.PreviewViewport;
import com.lw.modularmachine.util.client.gui.GuiTextures;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Internal shared structure viewer; data sources and extra editor actions
 * belong to subclasses.
 */
@ApiStatus.Internal
public abstract class StructurePreviewScreen extends Screen {
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
  private final List<EditBox> lengthFields = new ArrayList<>();
  private int materialScroll;
  private final PreviewDisplayState preview = new PreviewDisplayState(new PreviewHost());
  private final PreviewLengthInputs lengthInputs = new PreviewLengthInputs(
      preview,
      this::retainedDynamicKeys,
      this::dynamicLengthChanged);
  private final PreviewCandidatePanel candidates = new PreviewCandidatePanel(preview);

  private final class PreviewHost implements PreviewDisplayState.Host {
    @Override
    public int initialLength(String key, int minimum, int maximum) {
      return initialDynamicLength(key, minimum, maximum);
    }
    @Override
    public boolean failed() {
      return previewFailed();
    }
    @Override
    public void cleared() {
      if (minecraft != null) {
        rebuildFields();
      }
    }
    @Override
    public void selectionPublished() {
      rebuildFields();
    }
    @Override
    public void sceneChanged() {
      pickReady = false;
      dragging = false;
      candidates.select(-1);
    }
    @Override
    public void showLayer(Integer layer) {
      StructurePreviewScreen.this.showLayer(layer);
    }
  }

  protected final boolean preparingPreview() {
    return preview.preparing();
  }

  @Nullable
  protected final Map<String, Integer> completedPreviewLengths(MachinePreviewDefinition expected) {
    return preview.completedLengths(expected);
  }

  protected final void setPreview(MachinePreviewDefinition next, int nextColor) {
    preview.set(next, nextColor);
  }

  private final PreviewCamera camera = new PreviewCamera(89F);
  private boolean dragging, settingsOpen = true;
  private double dragDistance;
  private boolean pickReady;

  protected StructurePreviewScreen(Component title) {
    super(title);
  }

  protected Component headerTitle() {
    return title;
  }

  protected ItemStack headerIcon() {
    return ItemStack.EMPTY;
  }

  protected boolean hasHeaderModifier() {
    return false;
  }

  protected boolean canEditDynamicLengths() {
    return true;
  }

  protected int initialDynamicLength(String key, int minimum, int maximum) {
    return lengthInputs.initial(key, minimum, maximum);
  }

  protected final boolean previewFailed() {
    return lengthInputs.failed();
  }

  protected final String dynamicGroupKey(int index) {
    return preview.definition.segments().get(index).id();
  }

  /** Drafts may retain inputs for groups whose current world capture is empty. */
  protected Set<String> retainedDynamicKeys() {
    return preview.definition == null ? Set.of() : Set
        .copyOf(preview.definition.segments().stream().map(segment -> segment.id()).toList());
  }

  /**
   * Accepted input is a desired length; the incremental layout may still fail.
   */
  protected void dynamicLengthChanged(String key, int acceptedLength) {
  }

  protected record GroupAction(Component label, Runnable action) {
    public GroupAction {
      Objects.requireNonNull(label);
      Objects.requireNonNull(action);
    }
  }

  @Nullable
  protected GroupAction dynamicGroupAction(String key) {
    return null;
  }

  /** Register subclass widgets again after the shared controls are reconciled. */
  protected void rebuildAdditionalWidgets() {
  }

  protected record PreviewArea(int left, int top, int width, int height) {
    public PreviewArea {
      if (width < 0 || height < 0) {
        throw new IllegalArgumentException("Negative preview area");
      }
    }

    public boolean contains(double x, double y) {
      return x >= left && x < (double) left + width && y >= top && y < (double) top + height;
    }
  }

  protected PreviewArea previewArea(StructurePreviewPanel panel) {
    return new PreviewArea(
        panel.left() + 8,
        panel.top() + 48,
        Math.max(0, panel.width() - 16),
        Math.max(0, panel.height() - 90 - footerHeight()));
  }

  protected final boolean materialListVisible() {
    return settingsOpen && preview.selection != null;
  }

  protected final int materialListWidth() {
    return materialPanel().width();
  }

  protected void onPreviewBlockSelected(PreviewSceneData.Entry entry) {
  }

  /** Extra bottom space, below the shared layer controls. */
  protected int footerHeight() {
    return 0;
  }

  /**
   * Prepares source snapshots inside the shared tick budget and returns remaining
   * work.
   */
  protected int preparePreview(int budget, long deadline) {
    return budget;
  }

  protected final void resetPreviewCamera() {
    camera.reset();
    dragging = false;
    dragDistance = 0;
    pickReady = false;
  }

  protected Component mainGroupTitle() {
    return tr("main_structure");
  }

  protected Component dynamicGroupTitle(int index) {
    return tr("dynamic_layers", preview.definition.segments().get(index).id());
  }

  @Override
  protected void init() {
    rebuildFields();
    preview.lastCycle = Util.getMillis();
  }

  private void rebuildFields() {
    var focused = getFocused();
    if (focused == null) {
      for (var input : lengthInputs.fields()) {
        if (input.isFocused()) {
          focused = input;
          break;
        }
      }
    }
    clearWidgets();
    lengthFields.clear();
    var keys = new HashSet<>(retainedDynamicKeys());
    keys.add("");
    if (preview.definition != null && preview.selection != null) {
      for (int i = 0; i < preview.definition.segments().size(); i++) {
        String key = preview.definition.segments().get(i).id();
        if (canEditDynamicLengths()) {
          lengthFields.add(addWidget(lengthInputs.reconcile(font, dynamicGroupTitle(i), key, i, keys)));
        }
      }
    }
    lengthInputs.retain(keys);
    preview.includedGroups.keySet().retainAll(keys);
    rebuildAdditionalWidgets();
    if (focused != null && children().contains(focused)) {
      setFocused(focused);
    }
    positionFields(materialPanel());
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
    long deadline = System.nanoTime() + 2_000_000L;
    int budget = Math.clamp(preparePreview(2048, deadline), 0, 2048);
    preview.advance(budget, deadline);
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    super.render(graphics, mouseX, mouseY, partialTick);
    var panel = StructurePreviewPanel.forScreen(width, height);
    int left = panel.left();
    int top = panel.top();
    int panelWidth = panel.width();
    panel.drawBackground(graphics);
    panel.drawHeader(graphics, font, headerTitle(), headerIcon(), hasHeaderModifier());
    if (preview.selection == null) {
      if (preview.pendingSelection != null) {
        graphics.drawString(
            font,
            tr(previewFailed() ? "dynamic_invalid" : "dynamic_building"),
            left + 18,
            top + 34,
            previewFailed() ? 0xFFAA0000 : 0xFF606060,
            false);
      }
      return;
    }
    drawStatistics(graphics, left, top, panelWidth);
    if (preview.scene != null) {
      drawScene(graphics, panel);
    }
    drawSettingsButton(graphics, panel, mouseX, mouseY);
    positionFields(materialPanel());
    if (settingsOpen) {
      drawRequiredBlocks(graphics, mouseX, mouseY, partialTick);
    }
    candidates.draw(graphics, font, previewArea(panel), mouseX, mouseY);
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
    if (previewFailed()) {
      return tr("dynamic_invalid");
    }
    if (preparingPreview() || preview.statistics == null || !preview.statistics.ready()) {
      return tr("details.statistics_pending");
    }
    if (preview.shownY == null) {
      return tr(
          "details.dimensions",
          preview.statistics.width(),
          preview.statistics.height(),
          preview.statistics.depth(),
          preview.statistics.blocks);
    }
    return tr(
        "details.dimensions_layer",
        preview.statistics.width(),
        preview.statistics.height(),
        preview.statistics.depth(),
        preview.statistics.blocks,
        preview.statistics.layers.getOrDefault(preview.shownY, 0));
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

  protected record LayerControls(int left, int top, int labelWidth) {
    public LayerControls {
    }

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

  protected LayerControls layerControls() {
    int left = Math.max(12, (width - Math.min(760, width - 24)) / 2);
    int top = Math.max(12, (height - Math.min(900, height - 24)) / 2);
    int panelWidth = Math.min(760, width - left * 2);
    int panelHeight = Math.min(900, height - top * 2);
    int labelWidth = Math.max(40, Math.min(116, panelWidth - 132));
    int controlsWidth = labelWidth + 108;
    return new LayerControls(
        left + (panelWidth - controlsWidth) / 2,
        top + panelHeight - 28 - footerHeight(),
        labelWidth);
  }

  private Integer adjacentLayer(boolean next) {
    if (preview.statistics == null || !preview.statistics.ready() || preview.statistics.layers.isEmpty()) {
      return null;
    }
    if (preview.shownY == null) {
      return next ? preview.statistics.layers.firstKey() : preview.statistics.layers.lastKey();
    }
    return next ? preview.statistics.layers.higherKey(preview.shownY) : preview.statistics.layers
        .lowerKey(preview.shownY);
  }

  private void showLayer(Integer layer) {
    preview.shownY = layer;
    pickReady = false;
    dragging = false;
    candidates.select(-1);
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
    var label = preview.shownY == null ? tr("details.layer_all") : tr("details.layer_current", preview.shownY);
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
        preview.shownY == null ? 0xFF888888 : 0xFF202020,
        false);
    if (controls.contains(mouseX, mouseY)) {
      var action = mouseX < controls.labelX() ? tr("details.layer_previous") : mouseX >= controls.allX() ? tr(
          "details.layer_show_all") : mouseX >= controls.nextX() ? tr("details.layer_next") : label;
      graphics.renderTooltip(font, List.of(action, tr("details.layer_help")), Optional.empty(), mouseX, mouseY);
    }
  }

  private void drawSettingsButton(GuiGraphics graphics, StructurePreviewPanel panel, int mouseX, int mouseY) {
    var button = panel.settingsButton();
    int x = button.left();
    int y = button.top();
    graphics.blit(SETTINGS_BACKGROUND, x, y, 0, 0, button.width(), button.height(), 20, 22);
    graphics.blit(SETTINGS_ICON, x + 2, y + 3, 0, 0, 16, 16, 16, 16);
    if (button.containsPoint(mouseX, mouseY)) {
      graphics.renderOutline(x, y, button.width(), button.height(), 0xFF205A70);
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

  protected int materialPanelWidth(int availableWidth) {
    return Math.max(120, Math.min(REQUIRED_BLOCK_MAX_WIDTH, availableWidth));
  }

  private MaterialPanel materialPanel() {
    int left = Math.max(12, (width - Math.min(760, width - 24)) / 2);
    int top = Math.max(12, (height - Math.min(900, height - 24)) / 2);
    int parentWidth = Math.min(760, width - left * 2);
    int parentHeight = Math.min(900, height - top * 2);
    int panelWidth = materialPanelWidth(parentWidth - 36);
    int panelHeight = Math.max(74, Math.min(parentHeight - 92 - footerHeight(), contentHeight(panelWidth) + 42));
    return new MaterialPanel(left + parentWidth - panelWidth - 18, top + 58, panelWidth, panelHeight);
  }

  private int materialColumns(int panelWidth) {
    return Math.max(1, Math.min(REQUIRED_BLOCK_COLUMNS, (panelWidth - 16) / REQUIRED_BLOCK_HORIZONTAL_SPACING));
  }

  private int dynamicGroups() {
    return preview.definition == null ? 0 : preview.definition.segments().size();
  }

  private int dynamicCardHeight(int index) {
    int height = canEditDynamicLengths() ? DYNAMIC_CARD_HEIGHT : MAIN_CARD_HEIGHT;
    return height + (dynamicGroupAction(preview.definition.segments().get(index).id()) == null ? 0 : 22);
  }

  private int dynamicCardOffset(int index) {
    int offset = MAIN_CARD_HEIGHT + CARD_GAP;
    for (int i = 0; i < index; i++) {
      offset += dynamicCardHeight(i) + CARD_GAP;
    }
    return offset;
  }

  private int contentHeight(int panelWidth) {
    if (preview.selection == null) {
      return 0;
    }
    int columns = materialColumns(panelWidth);
    int rows = Math.max(1, (preview.selection.materials().size() + columns - 1) / columns);
    return dynamicCardOffset(dynamicGroups()) + 18 + rows * REQUIRED_BLOCK_VERTICAL_SPACING + 4;
  }

  private int maximumScroll(MaterialPanel panel) {
    return Math.max(0, contentHeight(panel.width()) - (panel.bodyBottom() - panel.bodyTop()));
  }

  private void positionFields(MaterialPanel panel) {
    materialScroll = Math.clamp(materialScroll, 0, maximumScroll(panel));
    int offset = MAIN_CARD_HEIGHT + CARD_GAP;
    for (int i = 0; i < lengthFields.size(); i++) {
      var field = lengthFields.get(i);
      int y = panel.bodyTop() - materialScroll + offset + 17;
      offset += dynamicCardHeight(i) + CARD_GAP;
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
    drawCard(graphics, panel, firstY, MAIN_CARD_HEIGHT, mainGroupTitle(), 0);
    int nextY = firstY + MAIN_CARD_HEIGHT + CARD_GAP;
    for (int i = 0; i < dynamicGroups(); i++) {
      int cardHeight = dynamicCardHeight(i);
      int y = nextY;
      nextY += cardHeight + CARD_GAP;
      if (y + cardHeight <= panel.bodyTop() || y >= panel.bodyBottom()) {
        continue;
      }
      var segment = preview.definition.segments().get(i);
      drawCard(graphics, panel, y, cardHeight, dynamicGroupTitle(i), i + 1);
      if (canEditDynamicLengths()) {
        int amount = hasShiftDown() ? 4 : 1;
        drawStepButton(
            graphics,
            panel.left() + 14,
            y + 17,
            "-" + amount,
            lengthInputs.desiredLength(i) > segment.minimum(),
            mouseX,
            mouseY);
        drawStepButton(
            graphics,
            panel.left() + panel.width() - 36,
            y + 17,
            "+" + amount,
            lengthInputs.desiredLength(i) < segment.maximum(),
            mouseX,
            mouseY);
        lengthFields.get(i).render(graphics, mouseX, mouseY, partialTick);
        if (panel.bodyContains(mouseX, mouseY) && mouseY >= y && mouseY < y + 38) {
          tooltip = List.of(
              dynamicGroupTitle(i),
              tr("dynamic_range", segment.minimum(), segment.maximum()),
              tr("dynamic_step_help"));
        }
      }
      var action = dynamicGroupAction(segment.id());
      if (action != null) {
        int actionX = panel.left() + 14;
        int actionY = y + cardHeight - 51;
        int actionWidth = groupActionWidth(panel, action);
        GuiTextures.blitNineSlice(graphics, CONTROL_TEXTURE, actionX, actionY, actionWidth, 18);
        graphics.drawString(
            font,
            font.plainSubstrByWidth(action.label().getString(), actionWidth - 8),
            actionX + 4,
            actionY + 5,
            0xFF202020,
            false);
        if (panel.bodyContains(mouseX, mouseY)
            && mouseX >= actionX
            && mouseX < actionX + actionWidth
            && mouseY >= actionY
            && mouseY < actionY + 18) {
          graphics.renderOutline(actionX, actionY, actionWidth, 18, 0xFF205A70);
          tooltip = List.of(action.label());
        }
      }
    }
    int materialsY = nextY;

    graphics.drawString(font, tr("required_blocks"), panel.left() + 8, materialsY + 3, 0xFF202020, false);
    int columns = materialColumns(panel.width());
    int index = 0;
    for (var entry : preview.selection.materials().entrySet()) {
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
    if (preview.selection.materials().isEmpty()) {
      graphics.drawString(font, tr("materials_empty"), panel.left() + 8, materialsY + 20, 0xFF606060, false);
    }
    graphics.disableScissor();
    boolean invalid = previewFailed();
    String status = invalid ? "dynamic_invalid" : preparingPreview() ? "dynamic_building" : "materials_help";
    graphics.drawString(
        font,
        font.plainSubstrByWidth(tr(status).getString(), panel.width() - 12),
        panel.left() + 6,
        panel.top() + panel.height() - 12,
        invalid ? 0xFFAA0000 : 0xFF606060,
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

  private int groupActionWidth(MaterialPanel panel, GroupAction action) {
    return Math.max(20, Math.min(panel.width() - 28, Math.max(40, font.width(action.label()) + 12)));
  }

  private void drawCard(GuiGraphics graphics, MaterialPanel panel, int y, int height, Component title, int group) {
    int x = panel.left() + 8;
    int width = panel.width() - 16;
    graphics.fill(x, y, x + width, y + height, 0xFFC6C6C6);
    graphics.renderOutline(x, y, width, height, 0xFF555555);
    graphics.fill(x + 1, y + 1, x + width - 1, y + 3, preview.selection.included(group) ? 0xFF52984A : 0xFF888888);
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
    var text = tr(preview.selection.included(group) ? "included_yes" : "included_no");
    graphics.drawString(
        font,
        text,
        toggleX + (INCLUDE_BUTTON_WIDTH - font.width(text)) / 2,
        textY,
        preview.selection.included(group) ? 0xFF2F721B : 0xFF606060,
        false);
  }

  private void drawStepButton(GuiGraphics graphics, int x, int y, String text, boolean active, int mouseX, int mouseY) {
    GuiTextures.blitNineSlice(graphics, CONTROL_TEXTURE, x, y, 22, 18);
    if (active && mouseX >= x && mouseX < x + 22 && mouseY >= y && mouseY < y + 18) {
      graphics.renderOutline(x, y, 22, 18, 0xFF205A70);
    }
    graphics.drawString(font, text, x + (22 - font.width(text)) / 2, y + 5, active ? 0xFF202020 : 0xFF888888, false);
  }

  private void drawScene(GuiGraphics graphics, StructurePreviewPanel panel) {
    var area = previewArea(panel);
    if (area.width() == 0 || area.height() == 0) {
      pickReady = false;
      return;
    }
    float available = Math.min(area.width(), area.height());
    float baseScale = Math.max(.01F, Math.min(24F, available * .55F / preview.selection.extent()));
    preview.renderer.render(
        graphics,
        new PreviewViewport(area.left(), area.top(), area.width(), area.height(), baseScale),
        camera,
        preview.shownY == null ? PreviewRenderer.ALL_LAYERS : preview.shownY,
        preview.step);
    pickReady = true;
  }

  @Override
  public boolean mouseClicked(double x, double y, int button) {
    if (preview.selection == null) {
      return super.mouseClicked(x, y, button);
    }
    var settings = StructurePreviewPanel.forScreen(width, height).settingsButton();
    if (button == 0 && settings.containsPoint((int) Math.floor(x), (int) Math.floor(y))) {
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
    var candidatePanel = candidates.panel(previewArea(StructurePreviewPanel.forScreen(width, height)));
    if (candidatePanel != null && candidatePanel.contains(x, y)) {
      setFocused(null);
      if (button == 0 && candidatePanel.closeContains(x, y)) {
        candidates.select(-1);
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
    var area = previewArea(StructurePreviewPanel.forScreen(width, height));
    if (area.contains(x, y)) {
      setFocused(null);
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
      int segment = -1;
      int offset = MAIN_CARD_HEIGHT + CARD_GAP;
      for (int i = 0; i < dynamicGroups(); i++) {
        if (localY >= offset && localY < offset + dynamicCardHeight(i)) {
          segment = i;
          break;
        }
        offset += dynamicCardHeight(i) + CARD_GAP;
      }
      if (segment < 0) {
        return false;
      }
      if (!retainedDynamicKeys().contains(dynamicGroupKey(segment))) {
        return true;
      }
      group = segment + 1;
      cardY = offset;
      cardHeight = dynamicCardHeight(segment);
      var action = dynamicGroupAction(preview.definition.segments().get(segment).id());
      if (action != null
          && localY >= cardY + cardHeight - 51
          && localY < cardY + cardHeight - 33
          && x >= panel.left() + 14
          && x < panel.left() + 14 + groupActionWidth(panel, action)) {
        setFocused(null);
        action.action().run();
        return true;
      }
      if (canEditDynamicLengths() && localY >= cardY + 17 && localY < cardY + 35) {
        int direction = 0;
        if (x >= panel.left() + 14 && x < panel.left() + 36) {
          direction = -1;
        } else if (x >= panel.left() + panel.width() - 36 && x < panel.left() + panel.width() - 14) {
          direction = 1;
        }
        if (direction != 0) {
          var bounds = preview.definition.segments().get(segment);
          long value = lengthInputs.desiredLength(segment) + (long) direction * (hasShiftDown() ? 4 : 1);
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
      preview.selection.toggleIncluded(group);
      preview.includedGroups
          .put(group == 0 ? "" : preview.definition.segments().get(group - 1).id(), preview.selection.included(group));
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
      if (preview.shownY == null) {
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
    PreviewSceneData.Entry picked = null;
    if (button == 0
        && dragDistance <= 4
        && preview.scene != null
        && pickReady
        && previewArea(StructurePreviewPanel.forScreen(width, height)).contains(x, y)) {
      candidates.select(preview.renderer.pick(x, y));
      if (candidates.selected() >= 0) {
        picked = preview.scene.entries().get(candidates.selected());
      }
    }
    dragging = false;
    if (picked != null) {
      onPreviewBlockSelected(picked);
    }
    return true;
  }

  @Override
  public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    if (getFocused() instanceof EditBox) {
      return super.keyPressed(keyCode, scanCode, modifiers);
    }
    if (keyCode == GLFW.GLFW_KEY_R) {
      resetPreviewCamera();
      return true;
    }
    if (keyCode == GLFW.GLFW_KEY_X && settingsOpen) {
      settingsOpen = false;
      positionFields(materialPanel());
      return true;
    }
    if (keyCode == GLFW.GLFW_KEY_X && candidates.selected() >= 0) {
      candidates.select(-1);
      return true;
    }
    return super.keyPressed(keyCode, scanCode, modifiers);
  }

  @Override
  public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
    if (preview.selection == null) {
      return super.mouseScrolled(x, y, scrollX, scrollY);
    }
    if (layerControls().contains(x, y)) {
      if (scrollY != 0) {
        moveLayer(scrollY < 0);
      }
      return true;
    }
    var candidatePanel = candidates.panel(previewArea(StructurePreviewPanel.forScreen(width, height)));
    if (candidatePanel != null && candidatePanel.contains(x, y)) {
      candidates.scroll(candidatePanel, scrollY);
      return true;
    }
    var panel = materialPanel();
    if (settingsOpen && panel.contains(x, y)) {
      materialScroll = (int) Math.clamp(materialScroll - Math.round(scrollY * 20), 0, maximumScroll(panel));
      positionFields(panel);
      return true;
    }
    if (!previewArea(StructurePreviewPanel.forScreen(width, height)).contains(x, y)) {
      return super.mouseScrolled(x, y, scrollX, scrollY);
    }
    camera.zoomBy(scrollY);
    return true;
  }

  @Override
  public void removed() {
    dragging = false;
    preview.renderer.close();
    super.removed();
  }
}
