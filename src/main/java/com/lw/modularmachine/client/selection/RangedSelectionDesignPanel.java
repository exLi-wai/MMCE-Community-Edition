package com.lw.modularmachine.client.selection;

import com.lw.modularmachine.client.preview.StructurePreviewPanel;
import com.lw.modularmachine.client.selection.RangedSelectionClient.Target;
import com.lw.modularmachine.machine.selection.RangedSelectionData;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Box;
import com.lw.modularmachine.machine.selection.RangedSelectionData.PartEdit;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Settings;
import com.lw.modularmachine.util.client.gui.GuiTextures;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** Internal client draft forms, independent of world snapshot publication. */
@ApiStatus.Internal
final class RangedSelectionDesignPanel {
  private enum Page {
    GLOBAL("global"),
    DYNAMIC("dynamic"),
    PART("part");

    final String label;

    Page(String label) {
      this.label = label;
    }
  }

  private static final Direction[] DIRECTIONS = {Direction.SOUTH, Direction.NORTH, Direction.WEST, Direction.EAST,
      Direction.UP, Direction.DOWN};
  private static final GuiTextures.NineSlice BUTTON = new GuiTextures.NineSlice(
      ResourceLocation.fromNamespaceAndPath("mmcece", "textures/gui/button.png"),
      0,
      0,
      200,
      20,
      200,
      20,
      2);

  private static final class Form {
    final Map<String, String> values = new LinkedHashMap<>();
    final Map<String, EditBox> editors = new LinkedHashMap<>();
    final Set<String> invalid = new HashSet<>();
    Component status = Component.empty();
    boolean error;
    int scroll;
  }

  private record Bounds(int left, int top, int width, int height) {
    int bottom() {
      return top + height;
    }

    int bodyTop() {
      return top + 30;
    }

    int bodyBottom() {
      return Math.max(bodyTop(), bottom() - 44);
    }

    int bodyWidth() {
      return Math.max(1, width - 20);
    }

    boolean contains(double x, double y) {
      return x >= left && x < left + width && y >= top && y < bottom();
    }

    boolean bodyContains(double x, double y) {
      return contains(x, y) && y >= bodyTop() && y < bodyBottom();
    }
  }

  private record Label(Component text, int y, int color) {
  }

  private record Control(Component label, int x, int y, int width, boolean enabled, Runnable action) {
    boolean contains(double mouseX, double mouseY, int left, int top) {
      return mouseX >= left + x && mouseX < left + x + width && mouseY >= top + y && mouseY < top + y + 18;
    }
  }

  private record Field(String key, EditBox widget, int x, int y, int width, boolean enabled) {
  }

  private final RangedConstructToolScreen host;
  private final Map<String, Form> forms = new LinkedHashMap<>();
  private final List<Label> labels = new ArrayList<>();
  private final List<Control> controls = new ArrayList<>();
  private final List<Field> fields = new ArrayList<>();
  private Page page = Page.GLOBAL;
  private boolean open, dirty = true;
  private Font font;
  private Form form;
  private String context = "";
  private int cursor;

  RangedSelectionDesignPanel(RangedConstructToolScreen host) {
    this.host = host;
  }

  boolean isOpen() {
    return open;
  }

  void toggle() {
    open = !open;
    rebuild();
  }

  void openSegment(String key) {
    if (!commitPending()) {
      return;
    }
    RangedSelectionClient.selectSegment(key);
    open = true;
    page = Page.DYNAMIC;
    rebuild();
  }

  void openPart() {
    host.focusDesignField(null);
    open = true;
    page = Page.PART;
    rebuild();
  }

  /** Validate the current page before another tool control leaves the editor. */
  boolean commitPending() {
    boolean accepted = form == null || apply();
    if (!accepted) {
      open = true;
      rebuild();
    }
    return accepted;
  }

  void selectionChanged() {
    forms.keySet().removeIf(key -> key.startsWith("part:"));
    if (context.startsWith("part:")) {
      form = null;
      context = "";
      host.focusDesignField(null);
    }
    invalidate();
  }

  int panelWidth(StructurePreviewPanel panel) {
    return Math.max(80, Math.min(panel.width() - 36, Math.max(112, Math.min(208, (panel.width() - 36) / 3))));
  }

  private Bounds bounds() {
    var panel = StructurePreviewPanel.forScreen(host.width, host.height);
    return new Bounds(panel.left() + 18, panel.top() + 58, panelWidth(panel), Math.max(0, panel.height() - 118));
  }

  private static Component tr(String key, Object... args) {
    return RangedSelectionClient.text("design." + key, args);
  }

  private String currentContext() {
    return switch (page) {
      case GLOBAL -> "global";
      case DYNAMIC -> "segment:" + RangedSelectionClient.selectedKey();
      case PART -> {
        var part = RangedSelectionClient.partEditor();
        yield part == null ? "part:none" : "part:" + part.position().asLong();
      }
    };
  }

  void rebuildWidgets(Font font, Consumer<EditBox> register) {
    this.font = font;
    dirty = false;
    forms.values().forEach(saved -> saved.editors.values().forEach(field -> field.visible = false));
    labels.clear();
    controls.clear();
    fields.clear();
    cursor = 0;
    if (!open || !RangedSelectionClient.valid()) {
      return;
    }
    context = currentContext();
    form = forms.computeIfAbsent(context, ignored -> loadForm());
    switch (page) {
      case GLOBAL -> globalPage();
      case DYNAMIC -> dynamicPage();
      case PART -> partPage();
    }
    positionFields();
    for (var field : fields) {
      if (field.widget().visible) {
        register.accept(field.widget());
      }
    }
  }

  private Form loadForm() {
    var result = new Form();
    var draft = RangedSelectionClient.design();
    if (page == Page.GLOBAL) {
      result.values.put("file_name", draft.fileName());
      result.values.put("target_file", draft.targetFile());
      result.values.put("append", Boolean.toString(draft.append()));
      result.values.put("deduplicate", Boolean.toString(draft.deduplicate()));
      result.values.put("include_air", Boolean.toString(draft.includeAir()));
      result.values.put("requires_blueprint", Boolean.toString(draft.requiresBlueprint()));
      result.values.put("has_factory", Boolean.toString(draft.hasFactory()));
      result.values.put("factory_only", Boolean.toString(draft.factoryOnly()));
      result.values.put("color", draft.color());
    } else if (page == Page.DYNAMIC) {
      var value = draft.segments().get(RangedSelectionClient.selectedSegment()).settings();
      result.values.put("id", value.id());
      result.values.put("direction", value.direction().getName());
      result.values.put("gap", Integer.toString(value.gap()));
      result.values.put("custom_step", Boolean.toString(value.customStep()));
      result.values.put("custom_origin", Boolean.toString(value.customOrigin()));
      vectorValues(result, "step", value.step());
      vectorValues(result, "origin", value.origin());
      result.values.put("sample", Integer.toString(value.sampleLength()));
      result.values.put("selector", value.selector());
      result.values.put("interface", value.interfaceType());
    } else {
      var part = RangedSelectionClient.partEditor();
      var value = part == null ? PartEdit.inherit() : part.edit();
      result.values.put("blocks", value.blocks());
      result.values.put("properties", value.properties());
      result.values.put("space", value.propertiesSpace());
      result.values.put("part_selector", Boolean.toString(value.changeSelector()));
      result.values.put("part_interface", Boolean.toString(value.changeInterface()));
      result.values.put("selector", value.selector());
      result.values.put("interface", value.interfaceType());
    }
    return result;
  }

  private static void vectorValues(Form target, String key, BlockPos value) {
    target.values.put(key + "_x", Integer.toString(value.getX()));
    target.values.put(key + "_y", Integer.toString(value.getY()));
    target.values.put(key + "_z", Integer.toString(value.getZ()));
  }

  private void globalPage() {
    var draft = RangedSelectionClient.design();
    label(tr("files_draft"));
    field("file_name", tr("file_name"), 256, false, !flag("append"));
    toggle("append");
    field("target_file", tr("target_file"), 256, false, flag("append"));
    toggle("deduplicate");
    toggle("include_air");
    field("color", tr("color"), 7, false, true);
    label(tr("color_help"));
    toggle("requires_blueprint");
    toggle("has_factory");
    toggle("factory_only");
    label(tr("factory_flags_help"));
    label(
        draft.controller() == null ? tr(
            "basis_temporary") : tr("basis_controller", draft.controller().toShortString()));
    button(tr("pick_controller"), () -> pick(Target.CONTROLLER), true);
    button(tr("clear_controller"), () -> clear(Target.CONTROLLER), draft.controller() != null);
    label(boxText(draft.main()));
    button(tr("clear_main"), () -> clear(Target.MAIN), draft.main() != null);
  }

  private void dynamicPage() {
    var draft = RangedSelectionClient.design();
    int selected = RangedSelectionClient.selectedSegment();
    var segment = draft.segments().get(selected);
    label(tr("segment_number", selected + 1, draft.segments().size()));
    pair(
        tr("previous"),
        () -> adjacentSegment(-1),
        selected > 0,
        tr("next"),
        () -> adjacentSegment(1),
        selected + 1 < draft.segments().size());
    button(
        tr("add_segment"),
        () -> afterApply(RangedSelectionClient::addSegment),
        draft.segments().size() < RangedSelectionData.MAX_SEGMENTS);
    button(tr("remove_segment"), () -> afterApply(RangedSelectionClient::removeSegment), true);
    field("id", tr("id"), 64, false, true);
    label(tr("export_range", segment.settings().maximum()));
    var direction = flag("custom_step") ? tr(
        "direction_custom") : tr("direction", RangedSelectionClient.text("direction." + value("direction")));
    button(direction, this::cycleDirection, true);
    field("gap", tr("gap"), 10, true, true);
    toggle("custom_step");
    vector("step", flag("custom_step"));
    toggle("custom_origin");
    vector("origin", flag("custom_origin"));
    field("sample", tr("sample"), 10, true, true);
    label(tr("sample_help"));
    field("selector", tr("selector"), 64, false, true);
    field("interface", tr("interface"), 64, false, true);
    selection(Target.DYNAMIC, "body", segment.body());
    selection(Target.START, "start", segment.start());
    selection(Target.END, "end", segment.end());
  }

  private void partPage() {
    button(tr("pick_part"), () -> pick(Target.PART), true);
    var part = RangedSelectionClient.partEditor();
    if (part == null) {
      label(tr("no_part"));
      return;
    }
    label(tr("part_position", part.position().toShortString()));
    label(tr("part_block", part.observedBlock()));
    label(tr("part_observed", part.observedProperties().isBlank() ? "-" : part.observedProperties()));
    field("blocks", tr("blocks"), 4096, false, true);
    field("properties", tr("properties"), 4096, false, true);
    button(tr("properties_space", tr(value("space"))), () -> {
      setValue("space", value("space").equals("local") ? "world" : "local");
      rebuild();
    }, true);
    button(tr("read_properties"), () -> {
      var current = RangedSelectionClient.partEditor();
      if (current != null) {
        setValue("properties", current.observedProperties().isBlank() ? "-" : current.observedProperties());
        setValue("space", "world");
        rebuild();
      }
    }, true);
    button(tr("clear_properties"), () -> setValue("properties", "-"), true);
    toggle("part_selector");
    field("selector", tr("selector"), 64, false, flag("part_selector"));
    toggle("part_interface");
    field("interface", tr("interface"), 64, false, flag("part_interface"));
    button(tr("reset_part"), () -> afterApply(() -> {
      boolean reset = RangedSelectionClient.savePart(null);
      if (reset) {
        forms.remove(context);
      }
      return reset;
    }), true);
  }

  private void selection(Target target, String key, @Nullable Box box) {
    pair(tr("pick_" + key), () -> pick(target), true, tr("clear_" + key), () -> clear(target), box != null);
    label(boxText(box));
  }

  private static Component boxText(@Nullable Box box) {
    return box == null ? tr("no_selection") : tr("box", box.first().toShortString(), box.second().toShortString());
  }

  private void label(Component text) {
    labels.add(new Label(text, cursor, 0xFF404040));
    cursor += Math.max(1, font.split(text, bounds().bodyWidth()).size()) * font.lineHeight + 5;
  }

  private void field(String key, Component label, int maximum, boolean numeric, boolean enabled) {
    label(label);
    addField(key, 0, bounds().bodyWidth(), maximum, numeric, enabled);
    cursor += 24;
  }

  private void vector(String prefix, boolean enabled) {
    label(tr(prefix));
    int width = Math.max(12, (bounds().bodyWidth() - 8) / 3);
    String[] axes = {"x", "y", "z"};
    for (int i = 0; i < axes.length; i++) {
      addField(prefix + "_" + axes[i], i * (width + 4), width, 11, true, enabled);
    }
    cursor += 24;
  }

  private void addField(String key, int x, int width, int maximum, boolean numeric, boolean enabled) {
    var target = form;
    var field = target.editors.get(key);
    if (field == null) {
      Component name = tr(key);
      if (key.startsWith("step_") || key.startsWith("origin_")) {
        int split = key.lastIndexOf('_');
        name = tr(key.substring(0, split)).copy()
            .append(" " + key.substring(split + 1).toUpperCase(java.util.Locale.ROOT));
      }
      field = new EditBox(font, 0, 0, width, 18, name);
      field.setMaxLength(maximum);
      if (numeric) {
        field.setFilter(value -> value.matches("-?[0-9]*"));
      }
      field.setValue(target.values.getOrDefault(key, ""));
      var editor = field;
      field.setResponder(value -> {
        target.values.put(key, value);
        target.invalid.remove(key);
        target.error = !target.invalid.isEmpty();
        target.status = target.error ? tr("invalid") : Component.empty();
        editor.setTextColor(0xFFE0E0E0);
      });
      target.editors.put(key, field);
    }
    field.setTextColor(target.invalid.contains(key) ? 0xFFFF7777 : 0xFFE0E0E0);
    fields.add(new Field(key, field, x, cursor, width, enabled));
  }

  private void button(Component label, Runnable action, boolean enabled) {
    controls.add(new Control(label, 0, cursor, bounds().bodyWidth(), enabled, action));
    cursor += 24;
  }

  private void pair(Component left, Runnable first, boolean firstEnabled, Component right, Runnable second, boolean secondEnabled) {
    int width = (bounds().bodyWidth() - 4) / 2;
    controls.add(new Control(left, 0, cursor, width, firstEnabled, first));
    controls.add(new Control(right, width + 4, cursor, width, secondEnabled, second));
    cursor += 24;
  }

  private void toggle(String key) {
    button(tr(key, tr(flag(key) ? "on" : "off")), () -> {
      setValue(key, Boolean.toString(!flag(key)));
      rebuild();
    }, true);
  }

  private String value(String key) {
    return form.values.getOrDefault(key, "");
  }

  private boolean flag(String key) {
    return Boolean.parseBoolean(value(key));
  }

  private void setValue(String key, String value) {
    var editor = form.editors.get(key);
    if (editor != null) {
      editor.setValue(value);
    } else {
      form.values.put(key, value);
      form.status = Component.empty();
      form.error = false;
    }
  }

  private void cycleDirection() {
    for (int i = 0; i < DIRECTIONS.length; i++) {
      if (DIRECTIONS[i].getName().equals(value("direction"))) {
        var direction = DIRECTIONS[(i + 1) % DIRECTIONS.length];
        String key = RangedSelectionClient.selectedKey();
        boolean accepted = RangedSelectionClient.update(draft -> {
          int index = draft.keys().indexOf(key);
          var previous = draft.segments().get(index).settings();
          return draft.withSettings(
              index,
              new Settings(
                  previous.id(),
                  1,
                  previous.maximum(),
                  direction,
                  previous.gap(),
                  false,
                  previous.step(),
                  previous.customOrigin(),
                  previous.origin(),
                  previous.sampleLength(),
                  previous.selector(),
                  previous.interfaceType()));
        }, true, false);
        if (accepted) {
          // Commit this button immediately without consuming unrelated unfinished fields.
          form.values.put("direction", direction.getName());
          form.values.put("custom_step", "false");
          form.invalid.removeAll(List.of("step_x", "step_y", "step_z"));
          form.error = !form.invalid.isEmpty();
          form.status = form.error ? tr("invalid") : tr("direction_applied");
        } else {
          fail(RangedSelectionClient.status());
        }
        rebuild();
        return;
      }
    }
  }

  private void adjacentSegment(int delta) {
    var draft = RangedSelectionClient.design();
    int next = RangedSelectionClient.selectedSegment() + delta;
    if (next >= 0 && next < draft.keys().size()) {
      openSegment(draft.keys().get(next));
    }
  }

  private void pick(Target target) {
    if (apply()) {
      RangedSelectionClient.beginPick(target);
    }
  }

  private void clear(Target target) {
    afterApply(() -> RangedSelectionClient.clear(target));
  }

  private void afterApply(Supplier<Boolean> action) {
    if (apply()) {
      if (!action.get()) {
        fail(RangedSelectionClient.status());
      }
      rebuild();
    }
  }

  private int number(String key, int minimum, int maximum) {
    try {
      int result = Integer.parseInt(value(key));
      if (result < minimum || result > maximum) {
        throw new NumberFormatException();
      }
      return result;
    } catch (NumberFormatException invalid) {
      form.invalid.add(key);
      throw new IllegalArgumentException("Invalid numeric field", invalid);
    }
  }

  private BlockPos vectorValue(String key) {
    return new BlockPos(
        number(key + "_x", Integer.MIN_VALUE, Integer.MAX_VALUE),
        number(key + "_y", Integer.MIN_VALUE, Integer.MAX_VALUE),
        number(key + "_z", Integer.MIN_VALUE, Integer.MAX_VALUE));
  }

  private boolean apply() {
    if (!RangedSelectionClient.valid() || form == null || !context.equals(currentContext())) {
      if (form != null) {
        fail(tr("not_available"));
      }
      return false;
    }
    form.invalid.clear();
    form.status = Component.empty();
    form.error = false;
    boolean accepted;
    try {
      accepted = switch (page) {
        case GLOBAL -> applyGlobal();
        case DYNAMIC -> applySettings();
        case PART -> applyPart();
      };
    } catch (IllegalArgumentException invalid) {
      if (form.status.getString().isEmpty()) {
        fail(tr("invalid"));
      }
      refreshFieldColors();
      return false;
    }
    if (accepted) {
      form.status = tr("saved");
      form.error = false;
    } else if (!form.error || form.status.getString().isEmpty()) {
      fail(RangedSelectionClient.status());
    }
    refreshFieldColors();
    return accepted;
  }

  private boolean applyGlobal() {
    var draft = RangedSelectionClient.design();
    String fileName = value("file_name").strip(), targetFile = value("target_file").strip();
    boolean append = flag("append"), deduplicate = flag("deduplicate"), includeAir = flag("include_air");
    boolean requiresBlueprint = flag("requires_blueprint"), hasFactory = flag("has_factory"),
        factoryOnly = flag("factory_only");
    String color = colorValue();
    if (draft.fileName().equals(fileName)
        && draft.targetFile().equals(targetFile)
        && draft.append() == append
        && draft.deduplicate() == deduplicate
        && draft.includeAir() == includeAir
        && draft.requiresBlueprint() == requiresBlueprint
        && draft.hasFactory() == hasFactory
        && draft.factoryOnly() == factoryOnly
        && draft.color().equals(color)) {
      return true;
    }
    boolean capture = draft.deduplicate() != deduplicate || draft.includeAir() != includeAir;
    return RangedSelectionClient.update(
        current -> current.withOptions(fileName, append, targetFile, deduplicate, includeAir)
            .withMachineFlags(requiresBlueprint, hasFactory, factoryOnly).withColor(color),
        capture,
        false);
  }

  private String colorValue() {
    String raw = value("color").strip();
    String color = raw.startsWith("#") ? raw.substring(1) : raw;
    if (!raw.isEmpty() && !color.matches("[0-9a-fA-F]{6}")) {
      form.invalid.add("color");
      fail(tr("color_invalid"));
      throw new IllegalArgumentException("Invalid casing color");
    }

    return color.toUpperCase(Locale.ROOT);
  }

  private boolean applySettings() {
    var draft = RangedSelectionClient.design();
    String key = context.substring("segment:".length());
    int index = draft.keys().indexOf(key);
    if (index < 0) {
      fail(tr("not_available"));
      return false;
    }
    String id = value("id").strip();
    if (!id.isEmpty() && !id.matches("[a-zA-Z0-9_.-]{1,64}")) {
      form.invalid.add("id");
      throw new IllegalArgumentException("Invalid segment id");
    }
    for (int i = 0; i < draft.segments().size(); i++) {
      if (i != index && !id.isEmpty() && id.equals(draft.segments().get(i).settings().id())) {
        form.invalid.add("id");
        fail(tr("duplicate_id"));
        return false;
      }
    }
    var previous = draft.segments().get(index).settings();
    var step = flag("custom_step") ? vectorValue("step") : previous.step();
    if (flag("custom_step") && step.equals(BlockPos.ZERO)) {
      form.invalid.addAll(List.of("step_x", "step_y", "step_z"));
      fail(tr("step_zero"));
      return false;
    }
    var origin = flag("custom_origin") ? vectorValue("origin") : previous.origin();
    Direction direction = Direction.byName(value("direction"));
    if (direction == null) {
      throw new IllegalArgumentException("Invalid local direction");
    }
    int gap = number("gap", 0, 1_000_000), sample = number("sample", 0, 4_194_304);
    var settings = new Settings(
        id,
        1,
        previous.maximum(),
        direction,
        gap,
        flag("custom_step"),
        step,
        flag("custom_origin"),
        origin,
        sample,
        value("selector").strip(),
        value("interface").strip());
    if (settings.equals(previous)) {
      return true;
    }
    return RangedSelectionClient.update(current -> {
      int currentIndex = current.keys().indexOf(key);
      if (currentIndex < 0) {
        throw new IllegalArgumentException("Missing segment");
      }
      int length = current.segments().get(currentIndex).settings().maximum();
      return current.withSettings(
          currentIndex,
          new Settings(
              settings.id(),
              1,
              length,
              settings.direction(),
              settings.gap(),
              settings.customStep(),
              settings.step(),
              settings.customOrigin(),
              settings.origin(),
              settings.sampleLength(),
              settings.selector(),
              settings.interfaceType()));
    }, true, false);
  }

  private boolean applyPart() {
    var part = RangedSelectionClient.partEditor();
    if (part == null) {
      return true;
    }
    var edit = new PartEdit(
        value("blocks"),
        value("properties"),
        value("space"),
        flag("part_selector"),
        value("selector"),
        flag("part_interface"),
        value("interface"));
    if (edit.equals(part.edit())) {
      return true;
    }
    boolean saved = RangedSelectionClient.savePart(edit);
    if (!saved) {
      form.invalid.addAll(List.of("blocks", "properties", "selector", "interface"));
    }
    return saved;
  }

  private void fail(Component message) {
    form.status = message;
    form.error = true;
  }

  private void refreshFieldColors() {
    form.editors.forEach((key, field) -> field.setTextColor(form.invalid.contains(key) ? 0xFFFF7777 : 0xFFE0E0E0));
  }

  private int maximumScroll(Bounds area) {
    return Math.max(0, cursor - (area.bodyBottom() - area.bodyTop()));
  }

  private void positionFields() {
    var area = bounds();
    form.scroll = Math.clamp(form.scroll, 0, maximumScroll(area));
    for (var field : fields) {
      var widget = field.widget();
      widget.setX(area.left() + 8 + field.x());
      widget.setY(area.bodyTop() + field.y() - form.scroll);
      widget.setWidth(field.width());
      widget.active = field.enabled();
      widget.visible = open && widget.getY() >= area.bodyTop() && widget.getY() + 18 <= area.bodyBottom();
      if (!widget.visible && widget.isFocused()) {
        host.focusDesignField(null);
      }
    }
  }

  void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
    if (!open || !RangedSelectionClient.valid()) {
      return;
    }
    if (dirty || !context.equals(currentContext())) {
      host.rebuildDesignWidgets();
    }
    if (font == null || form == null) {
      return;
    }
    var area = bounds();
    if (area.height() < 74) {
      return;
    }
    graphics.fill(area.left(), area.top(), area.left() + area.width(), area.bottom(), 0xE6D5D5D5);
    graphics.renderOutline(area.left(), area.top(), area.width(), area.height(), 0xFF555555);
    int tabWidth = (area.width() - 20) / 3;
    for (var tab : Page.values()) {
      var control = new Control(tr(tab.label), tab.ordinal() * (tabWidth + 2), 0, tabWidth, true, () -> {
      });
      drawControl(graphics, control, area.left() + 8, area.top() + 6, mouseX, mouseY);
      if (tab == page) {
        graphics.renderOutline(area.left() + 8 + control.x(), area.top() + 6, tabWidth, 18, 0xFF205A70);
      }
    }
    Component hovered = null;
    int bodyLeft = area.left() + 8, bodyTop = area.bodyTop() - form.scroll;
    graphics.enableScissor(area.left() + 2, area.bodyTop(), area.left() + area.width() - 2, area.bodyBottom());
    try {
      for (var label : labels) {
        int y = bodyTop + label.y();
        for (var line : font.split(label.text(), area.bodyWidth())) {
          if (y + font.lineHeight > area.bodyTop() && y < area.bodyBottom()) {
            graphics.drawString(font, line, bodyLeft, y, label.color(), false);
          }
          y += font.lineHeight;
        }
      }
      for (var control : controls) {
        if (bodyTop + control.y() + 18 <= area.bodyTop() || bodyTop + control.y() >= area.bodyBottom()) {
          continue;
        }
        drawControl(graphics, control, bodyLeft, bodyTop, mouseX, mouseY);
        if (area.bodyContains(mouseX, mouseY) && control.contains(mouseX, mouseY, bodyLeft, bodyTop)) {
          hovered = control.label();
        }
      }
      for (var field : fields) {
        if (field.widget().visible) {
          field.widget().render(graphics, mouseX, mouseY, partial);
        }
      }
    } finally {
      graphics.disableScissor();
    }
    var apply = applyControl();
    drawControl(graphics, apply, area.left() + 8, area.bottom() - 40, mouseX, mouseY);
    graphics.drawString(
        font,
        font.plainSubstrByWidth(form.status.getString(), area.bodyWidth()),
        area.left() + 8,
        area.bottom() - 15,
        form.error ? 0xFFAA0000 : 0xFF606060,
        false);
    int maximum = maximumScroll(area);
    int track = area.bodyBottom() - area.bodyTop();
    if (maximum > 0 && track > 0) {
      int thumb = Math.min(track, Math.max(12, track * track / Math.max(1, cursor)));
      int y = area.bodyTop() + (track - thumb) * form.scroll / maximum;
      graphics.fill(area.left() + area.width() - 4, y, area.left() + area.width() - 2, y + thumb, 0xFF707070);
    }
    if (area.contains(mouseX, mouseY) && mouseY >= area.bottom() - 18 && !form.status.getString().isEmpty()) {
      hovered = form.status;
    }
    if (hovered != null) {
      graphics.renderTooltip(font, font.split(hovered, 240), mouseX, mouseY);
    }
  }

  private Control applyControl() {
    boolean active = page != Page.PART || RangedSelectionClient.partEditor() != null;
    return new Control(tr(page == Page.PART ? "save_part" : "apply"), 0, 0, bounds().bodyWidth(), active, this::apply);
  }

  private void drawControl(GuiGraphics graphics, Control control, int left, int top, int mouseX, int mouseY) {
    int x = left + control.x(), y = top + control.y();
    GuiTextures.blitNineSlice(graphics, BUTTON, x, y, control.width(), 18);
    String text = font.plainSubstrByWidth(control.label().getString(), Math.max(1, control.width() - 6));
    graphics.drawString(
        font,
        text,
        x + (control.width() - font.width(text)) / 2,
        y + 5,
        control.enabled() ? 0xFF202020 : 0xFF888888,
        false);
    if (control.enabled() && control.contains(mouseX, mouseY, left, top)) {
      graphics.renderOutline(x, y, control.width(), 18, 0xFF205A70);
    }
  }

  boolean mouseClicked(double mouseX, double mouseY, int button) {
    if (!contains(mouseX, mouseY)) {
      return false;
    }
    if (button != 0 || form == null) {
      return true;
    }
    var area = bounds();
    int tabWidth = (area.width() - 20) / 3;
    if (mouseY >= area.top() + 6 && mouseY < area.top() + 24) {
      for (var tab : Page.values()) {
        int x = area.left() + 8 + tab.ordinal() * (tabWidth + 2);
        if (mouseX >= x && mouseX < x + tabWidth) {
          if (tab != page && !commitPending()) {
            return true;
          }
          host.focusDesignField(null);
          page = tab;
          rebuild();
          return true;
        }
      }
    }
    var apply = applyControl();
    if (apply.contains(mouseX, mouseY, area.left() + 8, area.bottom() - 40)) {
      host.focusDesignField(null);
      if (apply.enabled()) {
        apply.action().run();
      }
      return true;
    }
    if (area.bodyContains(mouseX, mouseY)) {
      for (var field : fields) {
        var editor = field.widget();
        if (editor.visible && editor.active && editor.mouseClicked(mouseX, mouseY, button)) {
          host.focusDesignField(editor);
          return true;
        }
      }
      for (var control : controls) {
        if (control.contains(mouseX, mouseY, area.left() + 8, area.bodyTop() - form.scroll)) {
          host.focusDesignField(null);
          if (control.enabled()) {
            control.action().run();
          }
          return true;
        }
      }
    }
    host.focusDesignField(null);
    return true;
  }

  boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    if (!contains(mouseX, mouseY)) {
      return false;
    }
    if (form != null && bounds().bodyContains(mouseX, mouseY)) {
      int next = (int) Math.clamp(form.scroll - Math.round(scrollY * 20), 0, maximumScroll(bounds()));
      if (next != form.scroll) {
        form.scroll = next;
        rebuild();
      }
    }
    return true;
  }

  boolean contains(double mouseX, double mouseY) {
    return open && RangedSelectionClient.valid() && bounds().height() >= 74 && bounds().contains(mouseX, mouseY);
  }

  void invalidate() {
    dirty = true;
  }

  private void rebuild() {
    dirty = true;
    if (Minecraft.getInstance().screen == host) {
      host.rebuildDesignWidgets();
    }
  }
}
