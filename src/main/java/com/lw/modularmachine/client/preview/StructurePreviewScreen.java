package com.lw.modularmachine.client.preview;

import com.lw.modularmachine.api.client.preview.PreviewCamera;
import com.lw.modularmachine.api.client.preview.PreviewRenderer;
import com.lw.modularmachine.api.client.preview.PreviewSceneData;
import com.lw.modularmachine.api.client.preview.PreviewViewport;
import com.lw.modularmachine.util.client.gui.GuiTextures;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
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
  private MachinePreviewDefinition definition;
  private MachinePreviewDefinition pendingDefinition;
  private MachinePreviewSelection pendingSelection;
  private int color, pendingColor;
  private MachinePreviewSelection selection;
  private final List<EditBox> lengthFields = new ArrayList<>();
  private final Map<String, LengthInput> lengthInputs = new LinkedHashMap<>();
  private final Map<String, Boolean> includedGroups = new LinkedHashMap<>();
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

  private static final class LengthInput {
    final EditBox field;
    int accepted;
    boolean valid = true;

    LengthInput(EditBox field, int accepted) {
      this.field = field;
      this.accepted = accepted;
    }
  }

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

  protected StructurePreviewScreen(Component title) {
    super(title);
  }

  protected Component headerTitle() {
    return title;
  }

  protected ItemStack headerIcon() {
    return ItemStack.EMPTY;
  }

  protected boolean canEditDynamicLengths() {
    return true;
  }

  protected int initialDynamicLength(String key, int minimum, int maximum) {
    var input = lengthInputs.get(key);

    return input != null && input.accepted >= minimum && input.accepted <= maximum ? input.accepted : minimum;
  }

  protected final String dynamicGroupKey(int index) {
    return definition.segments().get(index).id();
  }

  /** Drafts may retain inputs for groups whose current world capture is empty. */
  protected Set<String> retainedDynamicKeys() {
    return definition == null ? Set.of() : Set
        .copyOf(definition.segments().stream().map(segment -> segment.id()).toList());
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
    return settingsOpen && selection != null;
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

  /**
   * Live sources can finish the current display before capturing further changes.
   */
  protected final boolean preparingPreview() {
    return pendingSelection != null && pendingSelection.building()
        || selection != null && selection.building()
        || statistics != null && !statistics.ready();
  }

  /** Only completed, nonempty scenes may be exported by an editor subclass. */
  @Nullable
  protected final Map<String, Integer> completedPreviewLengths(MachinePreviewDefinition expected) {
    if (expected == null
        || definition != expected
        || pendingSelection != null
        || selection == null
        || scene == null
        || scene.entries().isEmpty()
        || preparingPreview()
        || previewFailed()) {
      return null;
    }
    var lengths = new LinkedHashMap<String, Integer>();
    for (int index = 0; index < definition.segments().size(); index++) {
      lengths.put(definition.segments().get(index).id(), selection.length(index));
    }

    return Map.copyOf(lengths);
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
    return tr("dynamic_layers", definition.segments().get(index).id());
  }

  /**
   * A replacement is published after its complete display layout has been built.
   */
  protected final void setPreview(MachinePreviewDefinition next, int nextColor) {
    if (next == null) {
      definition = null;
      pendingDefinition = null;
      selection = null;
      pendingSelection = null;
      scene = null;
      statistics = null;
      shownY = null;
      animated = List.of();
      pickReady = false;
      dragging = false;
      selectPosition(-1);
      renderer.close();
      if (minecraft != null) {
        rebuildFields();
      }
      return;
    }
    if (pendingDefinition == next && pendingColor == nextColor
        || pendingSelection == null && definition == next && color == nextColor) {
      return;
    }
    pendingDefinition = next;
    pendingColor = nextColor;
    int[] lengths = new int[next.segments().size()];
    for (int i = 0; i < lengths.length; i++) {
      var segment = next.segments().get(i);
      lengths[i] = initialDynamicLength(segment.id(), segment.minimum(), segment.maximum());
    }
    pendingSelection = new MachinePreviewSelection(next, nextColor, lengths);
  }

  @Override
  protected void init() {
    rebuildFields();
    lastCycle = Util.getMillis();
  }

  private void rebuildFields() {
    var focused = getFocused();
    if (focused == null) {
      for (var input : lengthInputs.values()) {
        if (input.field.isFocused()) {
          focused = input.field;
          break;
        }
      }
    }
    clearWidgets();
    lengthFields.clear();
    var keys = new HashSet<>(retainedDynamicKeys());
    keys.add("");
    if (definition != null && selection != null) {
      for (int i = 0; i < definition.segments().size(); i++) {
        String key = definition.segments().get(i).id();
        if (canEditDynamicLengths()) {
          var input = lengthInputs.get(key);
          if (input == null) {
            var field = new EditBox(font, 0, 0, 80, 18, dynamicGroupTitle(i));
            field.setMaxLength(10);
            field.setFilter(value -> value.matches("[0-9]*"));
            field.setValue(Integer.toString(selection.length(i)));
            input = new LengthInput(field, selection.length(i));
            lengthInputs.put(key, input);
            field.setResponder(value -> editLength(key, value));
          } else if (input.valid && input.accepted != selection.length(i)) {
            input.accepted = selection.length(i);
            input.field.setValue(Integer.toString(input.accepted));
          }
          validateLengthInput(key, input);
          input.field.active = keys.contains(key);
          lengthFields.add(addWidget(input.field));
        }
      }
    }
    lengthInputs.keySet().retainAll(keys);
    includedGroups.keySet().retainAll(keys);
    rebuildAdditionalWidgets();
    if (focused != null && children().contains(focused)) {
      setFocused(focused);
    }
    positionFields(materialPanel());
  }

  private static int segmentIndex(MachinePreviewDefinition source, String key) {
    if (source != null) {
      for (int i = 0; i < source.segments().size(); i++) {
        if (source.segments().get(i).id().equals(key)) {
          return i;
        }
      }
    }
    return -1;
  }

  private void editLength(String key, String value) {
    var input = lengthInputs.get(key);
    var target = pendingSelection == null ? selection : pendingSelection;
    var source = pendingSelection == null ? definition : pendingDefinition;
    int index = segmentIndex(source, key);
    boolean valid = false;
    if (input != null && target != null && index >= 0 && retainedDynamicKeys().contains(key)) {
      try {
        int requested = Integer.parseInt(value);
        valid = target.setLength(index, requested);
        if (valid && input.accepted != requested) {
          input.accepted = requested;
          dynamicLengthChanged(key, requested);
        }
      } catch (IllegalArgumentException | ArithmeticException invalid) {
        // Preserve raw text and the last complete scene while editing.
      }
    }
    if (input != null) {
      input.valid = valid;
      input.field.setTextColor(valid ? 0xFFE0E0E0 : 0xFFFF7777);
    }
  }

  private void validateLengthInput(String key, LengthInput input) {
    int index = segmentIndex(definition, key);
    input.valid = false;
    if (index >= 0) {
      var bounds = definition.segments().get(index);
      try {
        int requested = Integer.parseInt(input.field.getValue());
        input.valid = requested == selection.length(index)
            && requested >= bounds.minimum()
            && requested <= bounds.maximum();
      } catch (NumberFormatException invalid) {
        // Reattaching a field must not replace an unfinished number.
      }
    }
    input.field.setTextColor(input.valid ? 0xFFE0E0E0 : 0xFFFF7777);
  }

  private int desiredLength(int index) {
    var input = lengthInputs.get(definition.segments().get(index).id());
    return input == null ? selection.length(index) : input.accepted;
  }

  protected final boolean previewFailed() {
    return selection != null && selection.failed()
        || pendingSelection != null && pendingSelection.failed()
        || lengthInputs.entrySet().stream().anyMatch(
            entry -> !entry.getValue().valid
                && (segmentIndex(definition, entry.getKey()) >= 0
                    || segmentIndex(pendingDefinition, entry.getKey()) >= 0));
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
    if (pendingSelection != null) {
      budget -= pendingSelection.advance(budget, deadline);
      if (!pendingSelection.building() && (!pendingSelection.failed() || selection == null)) {
        for (int i = 0; i <= pendingDefinition.segments().size(); i++) {
          String key = i == 0 ? "" : pendingDefinition.segments().get(i - 1).id();
          if (!includedGroups.getOrDefault(key, true)) {
            pendingSelection.toggleIncluded(i);
          }
        }
        definition = pendingDefinition;
        color = pendingColor;
        selection = pendingSelection;
        pendingDefinition = null;
        pendingSelection = null;
        rebuildFields();
      }
    } else if (selection != null) {
      budget -= selection.advance(budget, deadline);
    }
    if (selection != null && selection.scene() != scene && selection.scene() != null) {
      scene = selection.scene();
      animated = scene.entries().stream().filter(entry -> entry.states().size() > 1).toList();
      renderer.setScene(scene);
      statistics = new SceneStatistics(scene);
      pickReady = false;
      dragging = false;
      selectPosition(-1);
    }
    if (statistics != null && !statistics.ready()) {
      statistics.advance(budget, deadline);
    }
    if (shownY != null && statistics != null && statistics.ready() && !statistics.layers.containsKey(shownY)) {
      showLayer(null);
    }
    if (cycle && !animated.isEmpty() && Util.getMillis() - lastCycle >= 1500) {
      step++;
      lastCycle = Util.getMillis();
    }
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    super.render(graphics, mouseX, mouseY, partialTick);
    var panel = StructurePreviewPanel.forScreen(width, height);
    int left = panel.left();
    int top = panel.top();
    int panelWidth = panel.width();
    panel.drawBackground(graphics);
    panel.drawHeader(graphics, font, headerTitle(), headerIcon());
    if (selection == null) {
      if (pendingSelection != null) {
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
    if (scene != null) {
      drawScene(graphics, panel);
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
    if (previewFailed()) {
      return tr("dynamic_invalid");
    }
    if (preparingPreview() || statistics == null || !statistics.ready()) {
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
    return definition == null ? 0 : definition.segments().size();
  }

  private int dynamicCardHeight(int index) {
    int height = canEditDynamicLengths() ? DYNAMIC_CARD_HEIGHT : MAIN_CARD_HEIGHT;
    return height + (dynamicGroupAction(definition.segments().get(index).id()) == null ? 0 : 22);
  }

  private int dynamicCardOffset(int index) {
    int offset = MAIN_CARD_HEIGHT + CARD_GAP;
    for (int i = 0; i < index; i++) {
      offset += dynamicCardHeight(i) + CARD_GAP;
    }
    return offset;
  }

  private int contentHeight(int panelWidth) {
    if (selection == null) {
      return 0;
    }
    int columns = materialColumns(panelWidth);
    int rows = Math.max(1, (selection.materials().size() + columns - 1) / columns);
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
      var segment = definition.segments().get(i);
      drawCard(graphics, panel, y, cardHeight, dynamicGroupTitle(i), i + 1);
      if (canEditDynamicLengths()) {
        int amount = hasShiftDown() ? 4 : 1;
        drawStepButton(
            graphics,
            panel.left() + 14,
            y + 17,
            "-" + amount,
            desiredLength(i) > segment.minimum(),
            mouseX,
            mouseY);
        drawStepButton(
            graphics,
            panel.left() + panel.width() - 36,
            y + 17,
            "+" + amount,
            desiredLength(i) < segment.maximum(),
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

  private void drawScene(GuiGraphics graphics, StructurePreviewPanel panel) {
    var area = previewArea(panel);
    if (area.width() == 0 || area.height() == 0) {
      pickReady = false;
      return;
    }
    float available = Math.min(area.width(), area.height());
    float baseScale = Math.max(.01F, Math.min(24F, available * .55F / selection.extent()));
    renderer.render(
        graphics,
        new PreviewViewport(area.left(), area.top(), area.width(), area.height(), baseScale),
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
    if (selection == null || selected < 0 || selected >= selection.parts().size()) {
      return null;
    }
    int count = selection.parts().get(selected).alternatives().size();
    if (count == 0) {
      return null;
    }
    var area = previewArea(StructurePreviewPanel.forScreen(width, height));
    if (area.width() < 56 || area.height() < 38) {
      return null;
    }
    int availableWidth = Math.max(36, Math.min(CANDIDATE_MAX_WIDTH, area.width() - 20));
    int columns = Math.max(1, Math.min(CANDIDATE_COLUMNS, (availableWidth - 16) / CANDIDATE_HORIZONTAL_SPACING));
    int panelWidth = Math.min(availableWidth, 16 + columns * CANDIDATE_HORIZONTAL_SPACING);
    long rows = (long) (count - 1) / columns + 1;
    int panelHeight = (int) Math.min(Math.max(38, area.height() - 2), 38L + rows * CANDIDATE_VERTICAL_SPACING);

    return new CandidatePanel(area.left() + 10, area.top() + 10, panelWidth, panelHeight, columns, count);
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
    if (selection == null) {
      return super.mouseClicked(x, y, button);
    }
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
      var action = dynamicGroupAction(definition.segments().get(segment).id());
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
          var bounds = definition.segments().get(segment);
          long value = desiredLength(segment) + (long) direction * (hasShiftDown() ? 4 : 1);
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
      includedGroups.put(group == 0 ? "" : definition.segments().get(group - 1).id(), selection.included(group));
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
    PreviewSceneData.Entry picked = null;
    if (button == 0
        && dragDistance <= 4
        && scene != null
        && pickReady
        && previewArea(StructurePreviewPanel.forScreen(width, height)).contains(x, y)) {
      selectPosition(renderer.pick(x, y));
      if (selected >= 0) {
        picked = scene.entries().get(selected);
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
    if (keyCode == GLFW.GLFW_KEY_X && selected >= 0) {
      selectPosition(-1);
      return true;
    }
    return super.keyPressed(keyCode, scanCode, modifiers);
  }

  @Override
  public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
    if (selection == null) {
      return super.mouseScrolled(x, y, scrollX, scrollY);
    }
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
    if (!previewArea(StructurePreviewPanel.forScreen(width, height)).contains(x, y)) {
      return super.mouseScrolled(x, y, scrollX, scrollY);
    }
    camera.zoomBy(scrollY);
    return true;
  }

  @Override
  public void removed() {
    dragging = false;
    renderer.close();
    super.removed();
  }
}
