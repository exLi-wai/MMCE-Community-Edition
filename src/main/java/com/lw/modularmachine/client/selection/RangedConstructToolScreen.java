package com.lw.modularmachine.client.selection;

import com.lw.modularmachine.api.client.preview.PreviewCamera;
import com.lw.modularmachine.api.client.preview.PreviewRenderer;
import com.lw.modularmachine.api.client.preview.PreviewSceneData;
import com.lw.modularmachine.api.client.preview.PreviewViewport;
import com.lw.modularmachine.item.RangedConstructToolItem;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Action;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Box;
import com.lw.modularmachine.machine.selection.RangedSelectionData.PartEdit;
import com.lw.modularmachine.machine.selection.RangedSelectionData.PickTarget;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Request;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Segment;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Settings;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Snapshot;
import com.lw.modularmachine.network.ProjectionPayloads;
import com.lw.modularmachine.network.RangedSelectionPayloads;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.Util;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;

/**
 * Client editor for the existing tool. Coordinates and file writes remain
 * server-owned.
 */
public final class RangedConstructToolScreen extends Screen {
  private enum Page {
    MAIN,
    FILES,
    ADVANCED,
    DETAILS,
    PART,
    RESULT
  }

  private record Label(Component text, int x, int y, int width) {
  }
  private record Hover(Component text, int x, int y, int width, int height) {
  }

  private static final int WIDTH = 280, HEIGHT = 213;
  private static final Direction[] DIRECTIONS = {Direction.SOUTH, Direction.NORTH, Direction.WEST, Direction.EAST,
      Direction.UP, Direction.DOWN};
  private final PreviewRenderer renderer = PreviewRenderer.create();
  private final PreviewCamera camera = new PreviewCamera(89.9F);
  private final Map<String, String> values = new LinkedHashMap<>();
  private final Map<String, EditBox> edits = new LinkedHashMap<>();
  private final List<Label> labels = new ArrayList<>();
  private final List<Hover> hovers = new ArrayList<>();
  private final List<ProjectionPayloads.Entry> incoming = new ArrayList<>();
  private Snapshot state;
  private Page page = Page.MAIN;
  private Direction direction;
  private boolean customStep, customOrigin, deduplicate, includeAir;
  private boolean partLocal, customPartSelector, customPartInterface;
  private int left, top, previewLength, picked = -1;
  private String filter = "";
  private Component localError;
  private Action pending;
  private long sentRevision, sentAt, lastPreviewRequest = -1;
  private Runnable afterReply;
  private RangedSelectionPayloads.Preview previewHeader;
  private PreviewSceneData scene;
  private boolean previewWaiting, dragging;
  private double dragDistance;
  private float previewScale = 1;

  public RangedConstructToolScreen(Snapshot state) {
    super(Component.translatable("item.mmcece.ranged_construct_tool"));
    this.state = state;
    loadValues();
    if (state.partEditor() != null) {
      loadPartValues();
      page = Page.PART;
    }
  }

  private static Component tr(String key, Object... args) {
    return Component.translatable("gui.mmcece.selection." + key, args);
  }

  private Segment segment() {
    return state.segments().isEmpty() ? new Segment(Settings.defaults(), null, null, null) : state.segments()
        .get(state.selected());
  }

  private void loadValues() {
    var settings = segment().settings();
    values.put("id", settings.id());
    values.put("minimum", Integer.toString(settings.minimum()));
    values.put("maximum", Integer.toString(settings.maximum()));
    values.put("gap", Integer.toString(settings.gap()));
    values.put("sample", Integer.toString(settings.sampleLength()));
    values.put("step_x", Integer.toString(settings.step().getX()));
    values.put("step_y", Integer.toString(settings.step().getY()));
    values.put("step_z", Integer.toString(settings.step().getZ()));
    values.put("origin_x", Integer.toString(settings.origin().getX()));
    values.put("origin_y", Integer.toString(settings.origin().getY()));
    values.put("origin_z", Integer.toString(settings.origin().getZ()));
    values.put("selector", settings.selector());
    values.put("interface", settings.interfaceType());
    values.put("filename", state.fileName());
    direction = settings.direction();
    customStep = settings.customStep();
    customOrigin = settings.customOrigin();
    deduplicate = state.deduplicate();
    includeAir = state.includeAir();
    previewLength = Math.max(settings.minimum(), Math.min(settings.maximum(), previewLength));
  }

  private void loadPartValues() {
    var editor = state.partEditor();
    if (editor == null) {
      return;
    }
    var edit = editor.edit();
    values.put("part_blocks", edit.blocks());
    values.put("part_properties", edit.properties());
    values.put("part_selector", edit.selector());
    values.put("part_interface", edit.interfaceType());
    partLocal = edit.propertiesSpace().equals("local");
    customPartSelector = edit.changeSelector();
    customPartInterface = edit.changeInterface();
  }

  @Override
  protected void init() {
    rememberEdits();
    left = (width - WIDTH) / 2;
    top = (height - HEIGHT) / 2;
    rebuild();
  }

  private void rememberEdits() {
    edits.forEach((key, edit) -> {
      if (key.equals("filter")) {
        filter = edit.getValue();
      } else {
        values.put(key, edit.getValue());
      }
    });
  }

  private void rebuild() {
    clearWidgets();
    edits.clear();
    labels.clear();
    hovers.clear();
    if (page == Page.PART && state.partEditor() == null) {
      page = Page.MAIN;
    }
    switch (page) {
      case MAIN -> mainPage();
      case FILES -> filesPage();
      case ADVANCED -> advancedPage();
      case DETAILS -> detailsPage();
      case PART -> partPage();
      case RESULT -> resultPage();
    }
    if (page != Page.MAIN && page != Page.PART) {
      button(8, 185, 70, tr("back"), () -> saveThen(() -> show(Page.MAIN)));
    }
    if (pending != null || state.busy()) {
      children().forEach(child -> {
        if (child instanceof AbstractWidget widget) {
          widget.active = false;
        }
      });
    }
  }

  private void show(Page next) {
    rememberEdits();
    page = next;
    rebuild();
  }

  private void mainPage() {
    label(tr("part_edits", state.partEdits()), 180, 9, 92);
    var targetLabel = state.append() && !state.fileLabel().isEmpty() ? tr("target_file", state.fileLabel()) : tr(
        state.append() ? "target_existing" : "target_new");
    var target = button(
        8,
        26,
        168,
        targetLabel,
        () -> saveThen(() -> send(Action.SET_MODE, PickTarget.NONE, state.append() ? 0 : 1, "", null)));
    target.setTooltip(Tooltip.create(tr("target_help", state.fileLabel())));
    button(180, 26, 92, tr("files"), () -> saveThen(() -> {
      show(Page.FILES);
      listFiles(0);
    })).active = state.append();
    pickButton(12, 56, 120, PickTarget.MAIN, "main").active = !state.append();
    pickButton(148, 56, 120, PickTarget.BODY, "body");
    boxLabels(state.main(), 12, 79, state.append());
    boxLabels(segment().body(), 148, 79, false);
    clearButton(92, 98, PickTarget.MAIN).active = !state.append() && state.main() != null;
    clearButton(228, 98, PickTarget.BODY).active = segment().body() != null;
    label(tr("segment", state.selected() + 1, Math.max(1, state.segments().size())), 148, 99, 78);
    field("id", 8, 130, 132, 64, false);
    field("minimum", 148, 130, 58, 10, true);
    field("maximum", 214, 130, 58, 10, true);
    button(8, 154, 132, tr("direction", tr("direction_" + direction.getName())), () -> {
      rememberEdits();
      int index = 0;
      while (DIRECTIONS[index] != direction) {
        index++;
      }
      direction = DIRECTIONS[(index + 1) % DIRECTIONS.length];
      rebuild();
    });
    button(
        148,
        154,
        60,
        tr("edit_part"),
        () -> saveThen(() -> send(Action.BEGIN_PICK, PickTarget.PART, state.selected(), "", null)))
        .setTooltip(Tooltip.create(tr("pick_part_help")));
    button(212, 154, 60, tr("advanced"), () -> saveThen(() -> show(Page.ADVANCED)));
    button(8, 185, 84, tr("close"), () -> saveThen(this::closeNow));
    button(98, 185, 84, tr("clear_draft"), () -> send(Action.CLEAR, PickTarget.NONE, 0, "", null));
    button(188, 185, 84, tr("result"), () -> saveThen(() -> {
      show(Page.RESULT);
      requestPreview();
    })).active = state.hasResult();
  }

  private Button pickButton(int x, int y, int width, PickTarget target, String key) {
    var button = button(
        x,
        y,
        width,
        tr(key),
        () -> saveThen(() -> send(Action.BEGIN_PICK, target, state.selected(), "", null)));
    button.setTooltip(Tooltip.create(tr("pick_help")));
    return button;
  }

  private Button clearButton(int x, int y, PickTarget target) {
    return addRenderableWidget(
        Button
            .builder(tr("clear"), ignored -> saveThen(() -> send(Action.CLEAR_BOX, target, state.selected(), "", null)))
            .bounds(left + x, top + y, 40, 16).build());
  }

  private void boxLabels(Box box, int x, int y, boolean inherited) {
    if (inherited) {
      label(tr("inherited_main"), x, y, 120);
      return;
    }
    if (box == null) {
      label(tr("not_selected"), x, y, 120);
      return;
    }
    label(Component.literal(box.first().toShortString()), x, y, 120);
    label(Component.literal(box.second().toShortString()), x, y + 10, 120);
    hovers.add(
        new Hover(tr("box", box.first().toShortString(), box.second().toShortString(), box.volume()), x, y, 120, 19));
  }

  private void filesPage() {
    var search = edit("filter", 8, 28, 174, 20, 256);
    search.setValue(filter);
    button(188, 28, 84, tr("search"), () -> {
      rememberEdits();
      listFiles(0);
    });
    for (int index = 0; index < Math.min(4, state.files().size()); index++) {
      var file = state.files().get(index);
      int y = 52 + index * 31;
      label(Component.literal(file.path()), 12, y + 3, 206);
      label(Component.literal(file.machine().toString()), 12, y + 13, 206);
      button(
          222,
          y + 5,
          46,
          tr("choose"),
          () -> send(Action.SELECT_FILE, PickTarget.NONE, 0, file.token(), () -> show(Page.MAIN)));
      hovers.add(
          new Hover(
              Component.literal(file.path()).append("\n").append(Component.translatable(file.label())),
              8,
              y,
              210,
              29));
    }
    button(94, 185, 32, Component.literal("<"), () -> listFiles(state.filePage() - 1)).active = state.filePage() > 0;
    label(Component.literal((state.filePage() + 1) + " / " + state.filePages()), 132, 191, 100);
    button(240, 185, 32, Component.literal(">"), () -> listFiles(state.filePage() + 1)).active = state
        .filePage() + 1 < state.filePages();
  }

  private void listFiles(int index) {
    send(Action.LIST_FILES, PickTarget.NONE, index, filter, null);
  }

  private void advancedPage() {
    field("filename", 8, 30, 168, 96, false).active = !state.append();
    field("gap", 184, 30, 88, 10, true);
    button(8, 66, 128, tr(customStep ? "step_custom" : "step_auto"), () -> {
      rememberEdits();
      customStep = !customStep;
      rebuild();
    });
    vectorFields("step", 144, 66, customStep);
    button(8, 98, 128, tr(customOrigin ? "origin_custom" : "origin_auto"), () -> {
      rememberEdits();
      customOrigin = !customOrigin;
      rebuild();
    });
    vectorFields("origin", 144, 98, customOrigin);
    button(8, 132, 128, tr("deduplicate", tr(deduplicate ? "on" : "off")), () -> {
      rememberEdits();
      deduplicate = !deduplicate;
      rebuild();
    });
    button(144, 132, 128, tr("air", tr(includeAir ? "on" : "off")), () -> {
      rememberEdits();
      includeAir = !includeAir;
      rebuild();
    });
    label(tr("axis_help"), 8, 158, 264);
    button(144, 185, 128, tr("details"), () -> saveThen(() -> show(Page.DETAILS)));
  }

  private void vectorFields(String prefix, int x, int y, boolean enabled) {
    for (int index = 0; index < 3; index++) {
      String axis = new String[]{"x", "y", "z"}[index];
      var field = edit(prefix + "_" + axis, x + index * 44, y, 40, 20, 11);
      field.setFilter(value -> value.matches("-?[0-9]*"));
      field.active = enabled;
      field.setTooltip(Tooltip.create(Component.literal(axis.toUpperCase(Locale.ROOT))));
    }
  }

  private void detailsPage() {
    button(
        8,
        26,
        26,
        Component.literal("<"),
        () -> saveThen(
            () -> send(Action.SELECT_SEGMENT, PickTarget.NONE, state.selected() - 1, "", null))).active = state
                .selected() > 0;
    label(tr("segment", state.selected() + 1, Math.max(1, state.segments().size())), 40, 32, 104);
    button(
        146,
        26,
        26,
        Component.literal(">"),
        () -> saveThen(
            () -> send(Action.SELECT_SEGMENT, PickTarget.NONE, state.selected() + 1, "", null))).active = state
                .selected() + 1 < state.segments().size();
    button(
        180,
        26,
        44,
        tr("add"),
        () -> saveThen(() -> send(Action.ADD_SEGMENT, PickTarget.NONE, 0, "", null))).active = state.segments()
            .size() < 64;
    button(
        228,
        26,
        44,
        tr("remove"),
        () -> saveThen(() -> send(Action.REMOVE_SEGMENT, PickTarget.NONE, state.selected(), "", null)));
    pickButton(8, 56, 88, PickTarget.START, "start");
    clearButton(100, 56, PickTarget.START).active = segment().start() != null;
    pickButton(144, 56, 88, PickTarget.END, "end");
    clearButton(232, 56, PickTarget.END).active = segment().end() != null;
    boxLabels(segment().start(), 8, 80, false);
    boxLabels(segment().end(), 144, 80, false);
    field("sample", 8, 116, 78, 10, true);
    field("selector", 94, 116, 86, 64, false);
    field("interface", 188, 116, 84, 64, false);
    label(tr("metadata_help"), 8, 152, 264);
    button(144, 185, 128, tr("advanced"), () -> saveThen(() -> show(Page.ADVANCED)));
  }

  private void partPage() {
    var editor = state.partEditor();
    label(tr("part_position", editor.position().toShortString()), 8, 25, 264);
    label(tr("part_observed", editor.observedBlock()), 8, 35, 264);
    hovers.add(
        new Hover(
            Component.literal(editor.position().toShortString()).append("\n").append(editor.observedBlock())
                .append("\n").append(editor.observedProperties()),
            8,
            24,
            264,
            20));
    label(tr("part_blocks"), 8, 47, 264);
    edit("part_blocks", 8, 57, 264, 18, 4096).setTooltip(Tooltip.create(tr("part_blocks_help")));
    label(tr("part_properties"), 8, 78, 264);
    edit("part_properties", 8, 88, 264, 18, 4096).setTooltip(Tooltip.create(tr("part_properties_help")));
    partButton(8, 110, 112, tr("part_read_properties"), () -> {
      rememberEdits();
      values.put("part_properties", editor.observedProperties().isBlank() ? "-" : editor.observedProperties());
      partLocal = false;
      rebuild();
    }).setTooltip(Tooltip.create(tr("part_read_properties_help")));
    partButton(124, 110, 84, tr(partLocal ? "part_space_local" : "part_space_world"), () -> {
      rememberEdits();
      partLocal = !partLocal;
      rebuild();
    }).setTooltip(Tooltip.create(tr("part_space_help")));
    partButton(212, 110, 60, tr("part_clear_properties"), () -> {
      rememberEdits();
      values.put("part_properties", "-");
      rebuild();
    });
    partButton(8, 132, 96, tr(customPartSelector ? "part_selector_custom" : "part_selector_inherit"), () -> {
      rememberEdits();
      customPartSelector = !customPartSelector;
      rebuild();
    }).setTooltip(Tooltip.create(tr("part_marker_help")));
    edit("part_selector", 110, 132, 162, 18, 64).active = customPartSelector;
    partButton(8, 154, 96, tr(customPartInterface ? "part_interface_custom" : "part_interface_inherit"), () -> {
      rememberEdits();
      customPartInterface = !customPartInterface;
      rebuild();
    }).setTooltip(Tooltip.create(tr("part_marker_help")));
    edit("part_interface", 110, 154, 162, 18, 64).active = customPartInterface;
    button(
        8,
        185,
        84,
        tr("back"),
        () -> savePartThen(() -> send(Action.CLOSE_PART, PickTarget.PART, state.selected(), "", null)));
    button(98, 185, 84, tr("part_reset"), () -> send(Action.RESET_PART, PickTarget.PART, state.selected(), "", null))
        .setTooltip(Tooltip.create(tr("part_reset_help")));
    button(188, 185, 84, tr("part_save"), () -> send(Action.UPDATE_PART, PickTarget.PART, state.selected(), "", null))
        .setTooltip(Tooltip.create(tr("part_save_help")));
  }

  private Button partButton(int x, int y, int width, Component text, Runnable action) {
    return addRenderableWidget(
        Button.builder(text, ignored -> action.run()).bounds(left + x, top + y, width, 18).build());
  }

  private PartEdit partEdit() {
    return new PartEdit(
        values.getOrDefault("part_blocks", ""),
        values.getOrDefault("part_properties", ""),
        partLocal ? "local" : "world",
        customPartSelector,
        customPartSelector ? values.getOrDefault("part_selector", "") : "",
        customPartInterface,
        customPartInterface ? values.getOrDefault("part_interface", "") : "");
  }

  private void savePartThen(Runnable next) {
    if (pending != null || state.busy() || state.partEditor() == null) {
      return;
    }
    rememberEdits();
    try {
      if (partEdit().equals(state.partEditor().edit())) {
        next.run();
      } else {
        send(Action.UPDATE_PART, PickTarget.PART, state.selected(), "", next);
      }
    } catch (IllegalArgumentException invalid) {
      localError = tr("invalid_parameters");
    }
  }

  private void resultPage() {
    label(tr("preview_length"), 172, 32, 100);
    button(172, 48, 28, Component.literal("-"), () -> changeLength(-1)).active = !previewWaiting
        && previewLength > segment().settings().minimum();
    label(Component.literal(Integer.toString(previewLength)), 205, 54, 34);
    button(244, 48, 28, Component.literal("+"), () -> changeLength(1)).active = !previewWaiting
        && previewLength < segment().settings().maximum();
    label(tr("range", segment().settings().minimum(), segment().settings().maximum()), 172, 80, 100);
    label(tr("preview_parts", scene == null ? 0 : scene.entries().size()), 172, 96, 100);
    label(Component.literal(segment().settings().id()), 172, 112, 100);
    button(172, 130, 100, tr("reset_camera"), camera::reset);
    label(tr("preview_controls"), 8, 167, 264);
    button(94, 185, 88, tr("refresh_preview"), this::requestPreview).active = !previewWaiting;
    button(188, 185, 84, tr("close"), this::closeNow);
    if (scene == null) {
      label(tr(previewWaiting ? "preview_waiting" : "preview_unavailable"), 14, 88, 146);
    }
  }

  private void changeLength(int delta) {
    previewLength += delta;
    requestPreview();
  }

  private void requestPreview() {
    if (!state.hasResult() || pending != null || state.busy()) {
      return;
    }
    previewWaiting = true;
    previewHeader = null;
    incoming.clear();
    send(Action.PREVIEW, PickTarget.NONE, previewLength, "", null);
  }

  private EditBox field(String key, int x, int y, int width, int maximum, boolean numeric) {
    label(tr(key), x, y - 10, width);
    var result = edit(key, x, y, width, 20, maximum);
    if (numeric) {
      result.setFilter(value -> value.matches("[0-9]*"));
    }
    return result;
  }

  private EditBox edit(String key, int x, int y, int width, int height, int maximum) {
    var result = new EditBox(font, left + x, top + y, width, height, tr(key));
    result.setMaxLength(maximum);
    result.setValue(values.getOrDefault(key, ""));
    edits.put(key, result);
    addRenderableWidget(result);
    return result;
  }

  private void label(Component text, int x, int y, int width) {
    labels.add(new Label(text, x, y, width));
  }

  private Button button(int x, int y, int width, Component text, Runnable action) {
    return addRenderableWidget(
        Button.builder(text, ignored -> action.run()).bounds(left + x, top + y, width, 20).build());
  }

  private int number(String key) {
    return Integer.parseInt(values.get(key));
  }

  private Settings settings() {
    return new Settings(
        values.get("id").trim(),
        number("minimum"),
        number("maximum"),
        direction,
        number("gap"),
        customStep,
        customStep ? new BlockPos(number("step_x"), number("step_y"), number("step_z")) : segment().settings().step(),
        customOrigin,
        customOrigin ? new BlockPos(number("origin_x"), number("origin_y"), number("origin_z")) : segment().settings()
            .origin(),
        number("sample"),
        values.get("selector").trim(),
        values.get("interface").trim());
  }

  private void saveThen(Runnable next) {
    if (pending != null || state.busy()) {
      return;
    }
    rememberEdits();
    try {
      var settings = settings();
      if (settings.equals(segment().settings())
          && deduplicate == state.deduplicate()
          && includeAir == state.includeAir()
          && values.get("filename").equals(state.fileName())) {
        next.run();
        return;
      }
      send(Action.UPDATE, PickTarget.NONE, state.selected(), values.get("filename"), next);
    } catch (IllegalArgumentException invalid) {
      localError = tr("invalid_parameters");
    }
  }

  private void send(Action action, PickTarget target, int index, String value, Runnable next) {
    if (pending != null || state.busy()) {
      return;
    }
    rememberEdits();
    try {
      var parameters = action == Action.UPDATE ? settings() : segment().settings();
      pending = action;
      sentRevision = state.revision();
      sentAt = Util.getMillis();
      afterReply = next;
      localError = null;
      RangedSelectionPayloads.request(
          new Request(
              state.session(),
              sentRevision,
              action,
              target,
              index,
              value,
              parameters,
              deduplicate,
              includeAir,
              action == Action.UPDATE_PART ? partEdit() : null));
      rebuild();
    } catch (IllegalArgumentException invalid) {
      pending = null;
      afterReply = null;
      localError = tr("invalid_parameters");
    }
  }

  public void accept(Snapshot value) {
    if (!state.session().equals(value.session())) {
      pending = null;
      afterReply = null;
      page = Page.MAIN;
      lastPreviewRequest = -1;
      clearPreview();
    }
    rememberEdits();
    var previous = state;
    state = value;
    boolean acknowledged = pending != null && value.revision() >= sentRevision && !value.busy();
    var completedAction = pending;
    var followUp = afterReply;
    if (acknowledged) {
      pending = null;
      afterReply = null;
    }
    if (!previous.session().equals(value.session()) || previous.revision() != value.revision()) {
      loadValues();
      if (!value.hasResult()) {
        clearPreview();
      }
    }
    var previousEditor = previous.partEditor();
    var nextEditor = value.partEditor();
    boolean openedPart = nextEditor != null
        && (!previous.session().equals(value.session())
            || previousEditor == null
            || !previousEditor.position().equals(nextEditor.position()));
    boolean savedPart = acknowledged
        && value.revision() > sentRevision
        && (completedAction == Action.UPDATE_PART || completedAction == Action.RESET_PART);
    if (nextEditor != null && (openedPart || savedPart)) {
      loadPartValues();
      page = Page.PART;
    } else if (nextEditor == null && page == Page.PART) {
      page = Page.MAIN;
    }
    if (acknowledged && completedAction == Action.PREVIEW && previewHeader == null) {
      // The server can acknowledge before it begins streaming; keep the old complete
      // scene.
      previewWaiting = value.hasResult();
    }
    rebuild();
    if (acknowledged
        && followUp != null
        && (!requiresRevisionAdvance(completedAction) || value.revision() > sentRevision)) {
      followUp.run();
    }
  }

  private static boolean requiresRevisionAdvance(Action action) {
    return action == Action.UPDATE
        || action == Action.SELECT_FILE
        || action == Action.UPDATE_PART
        || action == Action.RESET_PART
        || action == Action.CLOSE_PART;
  }

  public void acceptPreview(RangedSelectionPayloads.Preview value) {
    var packet = value.preview();
    if (page != Page.RESULT
        || !state.session().equals(value.session())
        || value.revision() != state.revision()
        || minecraft.level == null
        || !packet.dimension().equals(minecraft.level.dimension().location())
        || packet.epoch() != value.revision()
        || value.request() < lastPreviewRequest) {
      return;
    }
    if (packet.start() == -1) {
      lastPreviewRequest = value.request();
      incoming.clear();
      previewHeader = null;
      previewWaiting = false;
      localError = tr("preview_unavailable");
      if (pending == Action.PREVIEW) {
        pending = null;
        afterReply = null;
      }
      rebuild();
      return;
    }
    if (packet.start() == 0) {
      if (previewHeader == null && value.request() <= lastPreviewRequest) {
        return;
      }
      lastPreviewRequest = value.request();
      previewHeader = value;
      incoming.clear();
    }
    if (previewHeader == null
        || value.request() != previewHeader.request()
        || packet.total() != previewHeader.preview().total()
        || packet.epoch() != previewHeader.preview().epoch()
        || !packet.machine().equals(previewHeader.preview().machine())
        || value.color() != previewHeader.color()
        || !packet.origin().equals(previewHeader.preview().origin())
        || incoming.size() != packet.start()
        || packet.total() > 4_194_304) {
      return;
    }
    incoming.addAll(packet.entries());
    if (incoming.size() != packet.total()) {
      return;
    }
    var complete = incoming.stream()
        .map(entry -> new PreviewSceneData.Entry(entry.position(), entry.states(), entry.properties())).toList();
    scene = new PreviewSceneData(value.color(), complete);
    renderer.setScene(scene);
    previewScale = Math.max(.01F, Math.min(24F, 95F / scene.extent()));
    incoming.clear();
    previewHeader = null;
    previewWaiting = false;
    picked = -1;
    if (pending == Action.PREVIEW) {
      pending = null;
      afterReply = null;
    }
    rebuild();
  }

  private void clearPreview() {
    scene = null;
    previewHeader = null;
    incoming.clear();
    previewWaiting = false;
    picked = -1;
    renderer.close();
  }

  @Override
  public void tick() {
    if (minecraft.player == null
        || minecraft.level == null
        || !minecraft.level.dimension().location().equals(state.dimension())
        || !(minecraft.player.getMainHandItem().getItem() instanceof RangedConstructToolItem)) {
      closeNow();
      return;
    }
    if (pending != null && Util.getMillis() - sentAt > 30_000) {
      pending = null;
      afterReply = null;
      previewWaiting = false;
      localError = tr("request_timeout");
      rebuild();
    }
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  private void closeNow() {
    minecraft.setScreen(null);
  }

  @Override
  public void onClose() {
    if (page == Page.PART) {
      savePartThen(() -> send(Action.CLOSE_PART, PickTarget.PART, state.selected(), "", this::closeNow));
      return;
    }
    closeNow();
  }

  @Override
  public void removed() {
    incoming.clear();
    renderer.close();
    super.removed();
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    renderBackground(graphics, mouseX, mouseY, partialTick);
    SelectionPanel.draw(graphics, left, top, WIDTH, HEIGHT);
    if (page == Page.MAIN) {
      SelectionPanel.draw(graphics, left + 8, top + 52, 128, 64);
      SelectionPanel.draw(graphics, left + 144, top + 52, 128, 64);
    } else if (page == Page.FILES) {
      for (int index = 0; index < Math.min(4, state.files().size()); index++) {
        SelectionPanel.draw(graphics, left + 8, top + 52 + index * 31, 264, 29);
      }
    } else if (page == Page.RESULT && scene != null) {
      renderer.render(
          graphics,
          new PreviewViewport(left + 8, top + 26, 152, 138, previewScale, left + 84, top + 94),
          camera,
          PreviewRenderer.ALL_LAYERS,
          (int) (Util.getMillis() / 1500));
    }
    graphics.drawString(font, title, left + 8, top + 8, 0xFF404040, false);
    for (var label : labels) {
      drawClipped(graphics, label.text(), label.x(), label.y(), label.width(), 0xFF404040);
    }
    var status = localError != null ? localError : state.status();
    if (status.getString().isEmpty()) {
      status = tr(page == Page.PART ? "part_export_hint" : "export_hint");
    }
    drawClipped(graphics, status, 8, 175, 264, localError == null ? 0xFF6B5200 : 0xFFAA0000);
    // Screen.render would blur the panel again; render only its widgets here.
    for (var renderable : renderables) {
      renderable.render(graphics, mouseX, mouseY, partialTick);
    }
    if (over(mouseX, mouseY, 8, 174, 264, 10)) {
      graphics.renderTooltip(font, font.split(status, 320), mouseX, mouseY);
    }
    for (var hover : hovers) {
      if (over(mouseX, mouseY, hover.x(), hover.y(), hover.width(), hover.height())) {
        graphics.renderTooltip(font, font.split(hover.text(), 320), mouseX, mouseY);
      }
    }
    if (page == Page.RESULT
        && scene != null
        && picked >= 0
        && picked < scene.entries().size()
        && over(mouseX, mouseY, 8, 26, 152, 138)) {
      var part = scene.entries().get(picked);
      var lines = new ArrayList<Component>();
      lines.add(Component.literal(part.position().toShortString()));
      if (!part.states().isEmpty()) {
        lines.add(part.states().getFirst().getBlock().getName());
      }
      graphics.renderTooltip(font, lines, Optional.empty(), mouseX, mouseY);
    }
  }

  private void drawClipped(GuiGraphics graphics, Component text, int x, int y, int available, int color) {
    String content = text.getString();
    if (font.width(content) > available) {
      content = font.plainSubstrByWidth(content, Math.max(0, available - font.width("…"))) + "…";
    }
    graphics.drawString(font, content, left + x, top + y, color, false);
  }

  private boolean over(double x, double y, int startX, int startY, int width, int height) {
    return x >= left + startX && x < left + startX + width && y >= top + startY && y < top + startY + height;
  }

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    if (super.mouseClicked(mouseX, mouseY, button)) {
      return true;
    }
    if (page == Page.RESULT && over(mouseX, mouseY, 8, 26, 152, 138)) {
      dragging = true;
      dragDistance = 0;
      return true;
    }
    return false;
  }

  @Override
  public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
    if (!dragging) {
      return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }
    dragDistance += Math.abs(dx) + Math.abs(dy);
    if (button == 0) {
      camera.rotate(dx, dy);
    } else {
      camera.pan(dx, dy);
    }
    return true;
  }

  @Override
  public boolean mouseReleased(double mouseX, double mouseY, int button) {
    if (!dragging) {
      return super.mouseReleased(mouseX, mouseY, button);
    }
    dragging = false;
    if (button == 0 && dragDistance <= 4 && scene != null) {
      picked = renderer.pick(mouseX, mouseY);
    }
    return true;
  }

  @Override
  public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
    if (page == Page.RESULT && over(x, y, 8, 26, 152, 138)) {
      camera.zoomBy(scrollY);
      return true;
    }
    return super.mouseScrolled(x, y, scrollX, scrollY);
  }
}
