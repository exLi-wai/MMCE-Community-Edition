package com.lw.modularmachine.client.selection;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.block.MachinePortBlock;
import com.lw.modularmachine.client.preview.MachinePreviewDefinition;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.selection.PartOverrides;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Box;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Design;
import com.lw.modularmachine.machine.selection.RangedSelectionData.PartEdit;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Segment;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Settings;
import com.lw.modularmachine.machine.selection.SelectionGeometry;
import com.lw.modularmachine.network.MachineJeiPayloads;
import com.lw.modularmachine.network.MachineJeiPayloads.Part;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.util.minecraft.state.BlockStateValues;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal budgeted compiler for detached client preview and declaration
 * snapshots. It neither reads server variable files nor writes machine files.
 */
@ApiStatus.Internal
final class RangedSelectionPreviewCompiler {
  private static final int MAX_ALTERNATIVES = 4_194_304;
  private final Design design;
  private final BlockPos origin;
  private final MachineOrientation orientation;
  private final Function<BlockPos, BlockState> states;
  private final LongOpenHashSet assigned = new LongOpenHashSet();
  private final Map<BlockState, LocalState> localStates = new HashMap<>();
  private final Map<EditedState, List<BlockState>> editedStates = new HashMap<>();
  private final List<Part> fixed = new ArrayList<>();
  private final List<JsonObject> fixedDeclarations = new ArrayList<>();
  private final List<SegmentBuild> segments = new ArrayList<>();
  private final List<Part> declarations = new ArrayList<>();
  private final List<MachineJeiPayloads.Segment> descriptors = new ArrayList<>();
  private int segmentIndex, alternatives;
  private SegmentBuild segment;
  private Iterator<BlockPos> main;
  private boolean mainDone, assembling;
  private int assemblyList, assemblyPart;
  private List<List<Part>> assemblyLists;
  private Alternatives pending;
  private List<Part> pendingTarget;
  private List<JsonObject> pendingDeclarations;
  private JsonObject pendingDeclaration;
  private BlockPos pendingOffset;
  private EditedState pendingKey;
  private MachinePreviewDefinition definition;
  private RangedSelectionExportSnapshot exportSnapshot;

  private record LocalState(BlockState state, boolean local) {
  }

  private record EditedState(BlockState state, PartEdit edit) {
  }

  private record Choice(Property<?> property, List<Comparable<?>> values) {
  }

  private static final class Failure extends IllegalArgumentException {
    final Component detail;

    Failure(String key, Object... arguments) {
      detail = Component.translatable("gui.mmcece.selection." + key, arguments);
    }
  }

  RangedSelectionPreviewCompiler(Design design, BlockPos origin, MachineOrientation orientation, Function<BlockPos, BlockState> states) {
    this.design = design;
    this.origin = origin;
    this.orientation = orientation;
    this.states = states;
  }

  int advance(int budget, long deadline) {
    int used = 0;
    while (used < budget && definition == null && System.nanoTime() < deadline) {
      step();
      used++;
    }

    return used;
  }

  private void step() {
    if (pending != null) {
      if (pending.advance()) {
        var values = List.copyOf(pending.result);
        editedStates.put(pendingKey, values);
        pendingTarget.add(new Part(pendingOffset, values));
        pendingDeclarations.add(pendingDeclaration);
        pending = null;
        pendingDeclarations = null;
        pendingDeclaration = null;
      }
      return;
    }
    if (assembling) {
      assemble();
      return;
    }
    if (segment != null) {
      if (segment.advance()) {
        if (!segment.body.isEmpty()) {
          segments.add(segment);
        }
        segment = null;
      }
      return;
    }
    if (segmentIndex < design.segments().size()) {
      int index = segmentIndex++;
      var source = design.segments().get(index);
      if (source.body() == null) {
        if (source.start() != null || source.end() != null) {
          throw new Failure("preview_body_required");
        }
      } else {
        segment = new SegmentBuild(source, design.keys().get(index));
      }
      return;
    }
    if (!mainDone) {
      if (main == null) {
        main = positions(design.main());
      }
      if (!main.hasNext()) {
        mainDone = true;
        return;
      }
      var position = main.next().immutable();
      if (position.equals(design.controller())) {
        return;
      }
      var state = states.apply(position);
      if (state.isAir() && !design.includeAir()) {
        return;
      }
      if (assigned.contains(position.asLong())) {
        if (!design.deduplicate()) {
          throw new Failure("preview_main_overlap", position.toShortString());
        }
        return;
      }
      part(fixed, fixedDeclarations, null, position, SelectionGeometry.local(position, origin, orientation), state);
      return;
    }
    assemblyLists = new ArrayList<>();
    assemblyLists.add(fixed);
    for (var value : segments) {
      assemblyLists.add(value.head);
      assemblyLists.add(value.body);
      assemblyLists.add(value.tail);
      descriptors.add(value.descriptor());
    }
    assembling = true;
  }

  private void assemble() {
    if (assemblyList == assemblyLists.size()) {
      // The immutable publication copy is the bounded soft-budget tail.
      exportSnapshot = new RangedSelectionExportSnapshot(
          fixedDeclarations,
          segments.stream().map(SegmentBuild::exportPattern).toList());
      definition = MachinePreviewDefinition.captured(declarations, fixed.size(), descriptors);
      return;
    }
    var source = assemblyLists.get(assemblyList);
    if (assemblyPart == source.size()) {
      assemblyList++;
      assemblyPart = 0;
    } else {
      declarations.add(source.get(assemblyPart++));
    }
  }

  private final class SegmentBuild {
    final Segment source;
    final String key;
    final BlockPos base, step, start;
    final List<Part> head = new ArrayList<>(), body = new ArrayList<>(), tail = new ArrayList<>();
    final List<JsonObject> headDeclarations = new ArrayList<>(), bodyDeclarations = new ArrayList<>(),
        tailDeclarations = new ArrayList<>();
    int region;
    Iterator<BlockPos> positions;

    SegmentBuild(Segment source, String key) {
      this.source = source;
      this.key = key;
      for (var box : new Box[]{source.body(), source.start(), source.end()}) {
        if (box != null && design.controller() != null && contains(box, design.controller())) {
          throw new Failure("preview_controller_dynamic");
        }
      }
      var bounds = SelectionGeometry.bounds(source.body(), origin, orientation);
      base = bounds.min();
      step = SelectionGeometry.step(source.settings(), bounds);
      start = source.settings().customOrigin() ? source.settings().origin() : base;
      positions = positions(source.body());
    }

    boolean advance() {
      if (!positions.hasNext()) {
        if (region == 0 && body.isEmpty()) {
          return true;
        }
        region++;
        if (region == 3) {
          return true;
        }
        positions = positions(region == 1 ? source.start() : source.end());
        return false;
      }
      var position = positions.next().immutable();
      var state = states.apply(position);
      if (state.isAir() && !design.includeAir()) {
        return false;
      }
      if (!assigned.add(position.asLong())) {
        throw new Failure("preview_overlap", position.toShortString());
      }
      var local = SelectionGeometry.local(position, origin, orientation);
      var relative = SelectionGeometry.relative(local, base, step, region == 2 ? source.settings().sampleLength() : 0);
      var target = switch (region) {
        case 0 -> body;
        case 1 -> head;
        default -> tail;
      };
      var exported = switch (region) {
        case 0 -> bodyDeclarations;
        case 1 -> headDeclarations;
        default -> tailDeclarations;
      };
      part(target, exported, source.settings(), position, relative, state);
      return false;
    }

    MachineJeiPayloads.Segment descriptor() {
      int maximum = Math.min(4_194_304, (MachineJeiPayloads.MAX_PARTS - 1 - head.size() - tail.size()) / body.size());
      return new MachineJeiPayloads.Segment(key, 1, maximum, start, step, head.size(), body.size(), tail.size());
    }

    RangedSelectionExportSnapshot.Pattern exportPattern() {
      return new RangedSelectionExportSnapshot.Pattern(
          key,
          source.settings().id(),
          start,
          step,
          headDeclarations,
          bodyDeclarations,
          tailDeclarations);
    }
  }

  private void part(List<Part> target, List<JsonObject> exported, Settings defaults, BlockPos world, BlockPos offset, BlockState state) {
    var edit = design.partEdits().get(world);
    var original = declaration(state);
    if (defaults != null) {
      if (!defaults.selector().isEmpty()) {
        original.addProperty("selector", defaults.selector());
      }
      if (!defaults.interfaceType().isEmpty()) {
        original.addProperty("interface", defaults.interfaceType());
      }
    }
    var declaration = edit == null ? original : PartOverrides.apply(original, edit);
    declaration.add("offset", RangedSelectionExportSnapshot.coordinates(offset));
    if (edit == null || edit.blocks().isEmpty() && edit.properties().isEmpty()) {
      reserve(1);
      target.add(new Part(offset, List.of(localStates.computeIfAbsent(state, this::localState).state())));
      exported.add(declaration);
      return;
    }
    var key = new EditedState(state, edit);
    var previous = editedStates.get(key);
    if (previous != null) {
      reserve(previous.size());
      target.add(new Part(offset, previous));
      exported.add(declaration);
      return;
    }
    pending = new Alternatives(declaration);
    pendingKey = key;
    pendingTarget = target;
    pendingDeclarations = exported;
    pendingDeclaration = declaration;
    pendingOffset = offset;
  }

  /**
   * Actual captured appearance is retained until a visual declaration is edited.
   */
  private LocalState localState(BlockState state) {
    var values = new LinkedHashMap<Property<?>, Comparable<?>>();
    for (var entry : state.getValues().entrySet()) {
      var value = entry.getValue();
      if (value instanceof Direction direction) {
        value = orientation.toLocalDirection(direction);
      } else if (value instanceof Direction.Axis axis) {
        value = orientation.toLocalAxis(axis);
      }
      if (!entry.getKey().getPossibleValues().contains(value)) {
        return new LocalState(state, false);
      }
      values.put(entry.getKey(), value);
    }

    return new LocalState(BlockStateValues.apply(state, values), true);
  }

  /** Mirrors the retained export declaration's property-space fallback. */
  private JsonObject declaration(BlockState state) {
    var value = new JsonObject();
    if (state.getBlock() instanceof MachinePortBlock port) {
      value.addProperty("block", port.kind().name().toLowerCase(Locale.ROOT));
      return value;
    }
    value.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    if (!(state.getBlock() instanceof LiquidBlock)) {
      var local = localStates.computeIfAbsent(state, this::localState);
      var properties = new JsonObject();
      for (var entry : local.state().getValues().entrySet()) {
        properties.addProperty(entry.getKey().getName(), propertyName(entry.getKey(), entry.getValue()));
      }
      if (!properties.isEmpty()) {
        value.add("properties", properties);
        value.addProperty("properties_space", local.local() ? "local" : "world");
      }
    }

    return value;
  }

  private final class Alternatives {
    final List<String> names;
    final JsonObject properties;
    final List<BlockState> result = new ArrayList<>();
    final List<Choice> choices = new ArrayList<>();
    final Iterator<BlockState> ports;
    int candidate, combination, combinations;
    BlockState base;

    Alternatives(JsonObject declaration) {
      names = strings(declaration.get("block"));
      properties = declaration.has("properties") ? declaration.getAsJsonObject("properties") : new JsonObject();
      PortKind port = names.size() == 1 ? port(names.getFirst()) : null;
      if (port != null) {
        if (declaration.has("properties") || declaration.has("properties_space")) {
          throw new Failure("preview_property", names.getFirst());
        }
        ports = Stream
            .concat(
                MachineRegistries.PORTS.values().stream().map(holder -> holder.get()),
                MachineRegistries.NETWORK_PORTS.values().stream().map(holder -> holder.get()))
            .filter(block -> block.kind() == port).map(Block::defaultBlockState).iterator();
      } else {
        ports = null;
      }
    }

    boolean advance() {
      if (ports != null) {
        if (ports.hasNext()) {
          reserve(1);
          result.add(ports.next());
          return false;
        }
        if (result.isEmpty()) {
          throw new Failure("preview_unknown_block", names.getFirst());
        }
        return true;
      }
      if (base == null) {
        if (candidate == names.size()) {
          return true;
        }
        prepare(names.get(candidate++));
        return false;
      }
      var values = new LinkedHashMap<Property<?>, Comparable<?>>();
      int value = combination++;
      for (var choice : choices) {
        values.put(choice.property(), choice.values().get(value % choice.values().size()));
        value /= choice.values().size();
      }
      reserve(1);
      result.add(BlockStateValues.apply(base, values));
      if (combination == combinations) {
        base = null;
      }
      return false;
    }

    private void prepare(String name) {
      var id = ResourceLocation.tryParse(name);
      if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
        throw new Failure("preview_unknown_variable", name);
      }
      var block = BuiltInRegistries.BLOCK.get(id);
      base = block.defaultBlockState();
      choices.clear();
      combination = 0;
      combinations = 1;
      for (var entry : properties.entrySet()) {
        var property = block.getStateDefinition().getProperty(entry.getKey());
        if (property == null) {
          throw new Failure("preview_property", name + ": " + entry.getKey());
        }
        var values = new ArrayList<Comparable<?>>();
        for (String literal : strings(entry.getValue())) {
          values.add(
              property.getValue(literal)
                  .orElseThrow(() -> new Failure("preview_property", entry.getKey() + "=" + literal)));
        }
        if ((long) combinations * values.size() > MAX_ALTERNATIVES - alternatives) {
          throw new Failure("preview_alternatives_limit", MAX_ALTERNATIVES);
        }
        combinations *= values.size();
        choices.add(new Choice(property, values));
      }
    }
  }

  private void reserve(int count) {
    if (count > MAX_ALTERNATIVES - alternatives) {
      throw new Failure("preview_alternatives_limit", MAX_ALTERNATIVES);
    }
    alternatives += count;
  }

  private static PortKind port(String name) {
    for (var kind : PortKind.values()) {
      if (kind.name().equalsIgnoreCase(name) || kind.path().equalsIgnoreCase(name)) {
        return kind;
      }
    }
    return null;
  }

  private static List<String> strings(JsonElement value) {
    if (value.isJsonPrimitive()) {
      return List.of(value.getAsString());
    }
    var values = new ArrayList<String>();
    for (var entry : value.getAsJsonArray()) {
      values.add(entry.getAsString());
    }
    return values;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static String propertyName(Property<?> property, Comparable<?> value) {
    return ((Property) property).getName(value);
  }

  private static Iterator<BlockPos> positions(Box box) {
    if (box == null) {
      return List.<BlockPos>of().iterator();
    }
    return BlockPos.betweenClosed(
        Math.min(box.first().getX(), box.second().getX()),
        Math.min(box.first().getY(), box.second().getY()),
        Math.min(box.first().getZ(), box.second().getZ()),
        Math.max(box.first().getX(), box.second().getX()),
        Math.max(box.first().getY(), box.second().getY()),
        Math.max(box.first().getZ(), box.second().getZ())).iterator();
  }

  private static boolean contains(Box box, BlockPos position) {
    return position.getX() >= Math.min(box.first().getX(), box.second().getX())
        && position.getX() <= Math.max(box.first().getX(), box.second().getX())
        && position.getY() >= Math.min(box.first().getY(), box.second().getY())
        && position.getY() <= Math.max(box.first().getY(), box.second().getY())
        && position.getZ() >= Math.min(box.first().getZ(), box.second().getZ())
        && position.getZ() <= Math.max(box.first().getZ(), box.second().getZ());
  }

  static Component detail(Throwable failure) {
    if (failure instanceof Failure localized) {
      return localized.detail;
    }
    String message = failure.getMessage();
    if (message != null && message.startsWith("message.mmcece.selection.")) {
      return Component.translatable(message);
    }
    if (message == null || message.isBlank()) {
      message = failure.getClass().getSimpleName();
    }
    return Component.translatable(
        "gui.mmcece.selection.preview_invalid",
        message.length() > 512 ? message.substring(0, 512) : message);
  }

  boolean done() {
    return definition != null;
  }

  MachinePreviewDefinition definition() {
    return definition;
  }

  RangedSelectionExportSnapshot exportSnapshot() {
    return exportSnapshot;
  }
}
