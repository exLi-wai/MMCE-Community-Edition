package com.lw.modularmachine.machine.selection;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.orientation.StateOrientationAdapters;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Box;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Segment;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Settings;
import com.lw.modularmachine.machine.structure.DynamicLayoutValidator;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.machine.structure.MachineDefinitionFiles;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal pure editor; registry descriptors and world values are captured on
 * the server thread.
 */
@ApiStatus.Internal
public final class DynamicDefinitionEditor {
  public record Captured(BlockPos world, BlockState state, JsonObject part, Map<String, Long> fields) {
    public Captured(BlockPos world, BlockState state, JsonObject part) {
      this(world, state, part, Map.of());
    }

    public Captured {
      world = Objects.requireNonNull(world).immutable();
      Objects.requireNonNull(state);
      part = Objects.requireNonNull(part).deepCopy();
      fields = Map.copyOf(fields);
    }
  }

  public record Input(
      ResourceLocation machine,
      JsonObject original,
      MachineDefinitionFiles.Variables variables,
      MachineOrientation orientation,
      BlockPos controller,
      Box main,
      List<Segment> segments,
      boolean deduplicate,
      boolean includeAir,
      List<Captured> captured) {
    public Input {
      Objects.requireNonNull(machine);
      original = original == null ? null : original.deepCopy();
      Objects.requireNonNull(variables);
      Objects.requireNonNull(orientation);
      controller = Objects.requireNonNull(controller).immutable();
      segments = List.copyOf(segments);
      captured = List.copyOf(captured);
      if (segments.size() > RangedSelectionData.MAX_SEGMENTS || captured.size() > RangedSelectionData.MAX_CAPTURE) {
        throw new IllegalArgumentException("Selection exceeds the editor capture budget");
      }
    }
  }

  public record Result(
      JsonObject root,
      MachineDefinition definition,
      int mainParts,
      int dynamicParts,
      int deduplicated,
      int excludedController,
      List<String> segmentIds) {
    public Result {
      segmentIds = List.copyOf(segmentIds);
    }
  }

  private record Existing(JsonObject raw, MachineDefinition.Part part, MachineDefinition.Rule rule) {
  }

  private record Bounds(BlockPos min, BlockPos max) {
  }

  private static final Comparator<Captured> ORDER = Comparator
      .comparingInt((Captured value) -> vector(value.part().get("offset")).getY())
      .thenComparingInt(value -> vector(value.part().get("offset")).getX())
      .thenComparingInt(value -> vector(value.part().get("offset")).getZ());

  private final Input input;
  private final Map<BlockPos, Captured> captured = new HashMap<>();
  private final Map<BlockPos, Existing> fixed = new LinkedHashMap<>();
  private final Set<BlockPos> assigned = new HashSet<>();
  private final Set<BlockPos> moved = new HashSet<>();
  private final Set<String> protectedParts = new HashSet<>();
  private MachineDefinition original;
  private int dynamicParts;
  private int deduplicated;
  private int work;

  private DynamicDefinitionEditor(Input input) {
    this.input = input;
    for (var value : input.captured()) {
      if (captured.putIfAbsent(value.world(), value) != null) {
        throw new IllegalArgumentException("Duplicate captured world position");
      }
      if (!local(value.world(), input.controller(), input.orientation()).equals(vector(value.part().get("offset")))) {
        throw new IllegalArgumentException("Captured part has a different coordinate basis");
      }
    }
  }

  /**
   * Collects and deduplicates descriptors without accessing the live registry.
   */
  public static List<StateOrientationAdapters.BlockReference> references(Input input) {
    var references = new ArrayList<StateOrientationAdapters.BlockReference>();
    if (input.original() != null) {
      references.addAll(
          MachineDefinition.blockReferences(
              Map.of(input.machine(), input.variables().expand(input.original(), input.machine().toString()))));
    }
    if (!input.captured().isEmpty()) {
      var root = new JsonObject();
      var parts = new JsonArray();
      // The reference reader does not interpret offsets or part IDs.
      var declarations = new HashSet<JsonObject>();
      for (var value : input.captured()) {
        var part = value.part().deepCopy();
        part.remove("offset");
        if (declarations.add(part)) {
          var declaration = part.deepCopy();
          declaration.add("offset", coordinates(BlockPos.ZERO));
          parts.add(declaration);
        }
      }
      root.add("parts", parts);
      references.addAll(
          MachineDefinition
              .blockReferences(Map.of(input.machine(), input.variables().expand(root, input.machine().toString()))));
    }
    var grouped = new LinkedHashMap<ResourceLocation, Map<String, StateOrientationAdapters.PropertyReference>>();
    var paths = new HashMap<ResourceLocation, String>();
    for (var reference : references) {
      var properties = grouped.computeIfAbsent(reference.id(), ignored -> new LinkedHashMap<>());
      paths.putIfAbsent(reference.id(), reference.path());
      for (var property : reference.properties()) {
        properties.putIfAbsent(property.name() + "\u0000" + property.literal(), property);
      }
    }
    var result = new ArrayList<StateOrientationAdapters.BlockReference>();
    grouped.forEach(
        (id, properties) -> result
            .add(new StateOrientationAdapters.BlockReference(id, paths.get(id), List.copyOf(properties.values()))));
    return List.copyOf(result);
  }

  public static Result edit(Input input, StateOrientationAdapters.RegistrySnapshot registry) {
    return new DynamicDefinitionEditor(input).edit(registry);
  }

  private Result edit(StateOrientationAdapters.RegistrySnapshot registry) {
    JsonObject root;
    if (input.original() == null) {
      if (input.main() == null) {
        throw new IllegalArgumentException("Select the fixed main body before exporting a new machine");
      }
      root = new JsonObject();
      root.addProperty(
          "translation_key",
          "machine." + input.machine().getNamespace() + "." + input.machine().getPath().replace('/', '.'));
      var orientation = new JsonObject();
      orientation.addProperty("policy", "all_24");
      root.add("orientation", orientation);
    } else {
      root = input.original().deepCopy();
      readOriginal(registry);
    }
    String dynamicKey = root.has("dynamic-patterns") ? "dynamic-patterns" : "dynamic_patterns";
    var patterns = root.has(dynamicKey) ? root.getAsJsonArray(dynamicKey) : new JsonArray();
    var existingPatterns = new LinkedHashMap<String, JsonObject>();
    for (var element : patterns) {
      var pattern = element.getAsJsonObject();
      String id = pattern.has("id") ? pattern.get("id").getAsString() : pattern.get("name").getAsString();
      existingPatterns.put(id, pattern);
    }
    var usedIds = new HashSet<>(existingPatterns.keySet());
    var requestedIds = new HashSet<String>();
    int selectedSegments = 0;
    var segmentIds = new ArrayList<String>();
    for (var segment : input.segments()) {
      if (segment.body() == null) {
        if (segment.start() != null || segment.end() != null) {
          throw new IllegalArgumentException(
              "A dynamic segment with a start/end selection also needs a repeating body");
        }
        segmentIds.add("");
        continue;
      }
      selectedSegments++;
      String id = segment.settings().id();
      if (id.isEmpty()) {
        int suffix = 1;
        while (usedIds.contains("segment_" + suffix)) {
          suffix++;
        }
        id = "segment_" + suffix;
      }
      if (!requestedIds.add(id)) {
        throw new IllegalArgumentException("Duplicate selected segment ID: " + id);
      }
      usedIds.add(id);
      segmentIds.add(id);
      var pattern = pattern(segment, id);
      var previous = existingPatterns.get(id);
      if (previous != null) {
        if (!canonicalPattern(previous).equals(canonicalPattern(pattern))) {
          throw new IllegalArgumentException("A different dynamic segment already uses ID " + id);
        }
      } else {
        patterns.add(pattern);
      }
    }
    if (input.original() != null && selectedSegments == 0) {
      throw new IllegalArgumentException("Select a dynamic repeating body before appending to a machine");
    }
    if (patterns.size() > RangedSelectionData.MAX_SEGMENTS) {
      throw new IllegalArgumentException("At most 64 dynamic segments are supported");
    }
    if (!patterns.isEmpty()) {
      root.add(dynamicKey, patterns);
    }
    var parts = new JsonArray();
    int excludedController = 0;
    if (input.original() == null) {
      for (var value : selection(input.main(), false)) {
        if (value.world().equals(input.controller())) {
          excludedController++;
          continue;
        }
        if (assigned.contains(value.world())) {
          if (!input.deduplicate()) {
            throw new IllegalArgumentException(
                "Main body overlaps a dynamic selection at " + value.world().toShortString());
          }
          deduplicated++;
          continue;
        }
        parts.add(value.part().deepCopy());
      }
    } else {
      for (var element : input.original().getAsJsonArray("parts")) {
        if (!moved.contains(vector(element.getAsJsonObject().get("offset")))) {
          parts.add(element.deepCopy());
        }
      }
    }
    if (parts.isEmpty()) {
      throw new IllegalArgumentException(
          "At least one fixed part besides the controller must remain after deduplication");
    }
    root.add("parts", parts);
    var definition = MachineDefinition
        .parse(input.machine(), input.variables().expand(root, input.machine().toString()), registry);
    if (!definition.allows(input.orientation())) {
      throw new IllegalArgumentException(
          "The complete definition cannot represent the selected controller orientation");
    }
    if (definition.parts().size() != parts.size()) {
      throw new IllegalArgumentException("The fixed definition contains unavailable block constraints");
    }
    for (int index = 0; index < definition.dynamicPatterns().size(); index++) {
      var pattern = definition.dynamicPatterns().get(index);
      var declaration = patterns.get(index).getAsJsonObject();
      if (pattern.body().size() != declaration.getAsJsonArray("parts").size()
          || pattern.start().size() != arraySize(declaration, "parts_start", "parts-start")
          || pattern.end().size() != arraySize(declaration, "parts_end", "parts-end")) {
        throw new IllegalArgumentException(
            "Dynamic segment " + pattern.id() + " contains unavailable block constraints");
      }
    }
    DynamicLayoutValidator.validate(definition);
    return new Result(root, definition, parts.size(), dynamicParts, deduplicated, excludedController, segmentIds);
  }

  private void readOriginal(StateOrientationAdapters.RegistrySnapshot registry) {
    original = MachineDefinition
        .parse(input.machine(), input.variables().expand(input.original(), input.machine().toString()), registry);
    if (!original.allows(input.orientation())) {
      throw new IllegalArgumentException("The selected controller orientation is not allowed by the original file");
    }
    var rules = original.rulesFor(input.orientation());
    var parsed = new HashMap<BlockPos, Integer>();
    for (int index = 0; index < original.parts().size(); index++) {
      parsed.put(original.parts().get(index).offset(), index);
    }
    for (var modifier : original.structureModifiers()) {
      for (int index : modifier.partIndices()) {
        protectedParts.add(original.parts().get(index).id());
      }
    }
    for (var element : input.original().getAsJsonArray("parts")) {
      var raw = element.getAsJsonObject();
      var position = vector(raw.get("offset"));
      Integer index = parsed.get(position);
      if (index == null) {
        throw new IllegalArgumentException(
            "The original file has an unavailable or ambiguous fixed part at " + position.toShortString());
      }
      if (fixed.putIfAbsent(position, new Existing(raw, original.parts().get(index), rules.get(index))) != null) {
        throw new IllegalArgumentException("The original file has duplicate fixed coordinates");
      }
    }
  }

  private JsonObject pattern(Segment segment, String id) {
    var settings = segment.settings();
    var bounds = bounds(segment.body());
    BlockPos step = step(settings, bounds);
    var result = new JsonObject();
    result.addProperty("id", id);
    result.addProperty("minimum", settings.minimum());
    result.addProperty("maximum", settings.maximum());
    result.add("start_offset", coordinates(settings.customOrigin() ? settings.origin() : bounds.min()));
    result.add("step", coordinates(step));
    var body = template(segment.body(), settings, bounds.min(), BlockPos.ZERO, 0);
    if (body.isEmpty()) {
      throw new IllegalArgumentException("Dynamic body " + id + " has no selected parts");
    }
    result.add("parts", body);
    if (segment.start() != null) {
      result.add("parts_start", template(segment.start(), settings, bounds.min(), BlockPos.ZERO, 0));
    }
    if (segment.end() != null) {
      result.add("parts_end", template(segment.end(), settings, bounds.min(), step, settings.sampleLength()));
    }
    return result;
  }

  private JsonArray template(Box box, Settings settings, BlockPos base, BlockPos step, int repetition) {
    var result = new JsonArray();
    for (var value : selection(box, true)) {
      if (!assigned.add(value.world())) {
        throw new IllegalArgumentException("Dynamic selections overlap at " + value.world().toShortString());
      }
      var absolute = vector(value.part().get("offset"));
      var previous = fixed.get(absolute);
      JsonObject part;
      if (previous == null) {
        part = value.part().deepCopy();
        if (!settings.selector().isEmpty()) {
          part.addProperty("selector", settings.selector());
        }
        if (!settings.interfaceType().isEmpty()) {
          part.addProperty("interface", settings.interfaceType());
        }
      } else {
        if (!input.deduplicate()) {
          throw new IllegalArgumentException("Dynamic selection overlaps fixed part " + previous.part().id());
        }
        validateMove(previous, value, settings);
        part = previous.raw().deepCopy();
        if (!part.has("part_id")) {
          part.addProperty("part_id", previous.part().id());
        }
        moved.add(absolute);
        deduplicated++;
      }
      part.add("offset", coordinates(relative(absolute, base, step, repetition)));
      result.add(part);
      dynamicParts++;
    }
    return result;
  }

  private void validateMove(Existing previous, Captured value, Settings settings) {
    var part = previous.part();
    if (protectedParts.contains(part.id())) {
      throw new IllegalArgumentException("Fixed part " + part.id() + " is referenced by a structure modifier");
    }
    if (original.energyPerTick() > 0
        && (part.rule().port() == PortKind.ENERGY_INPUT || part.rule().port() == PortKind.ENERGY_OUTPUT)) {
      throw new IllegalArgumentException("The fixed energy transfer port cannot move into a dynamic segment");
    }
    if (!previous.rule().matches(value.state())) {
      throw new IllegalArgumentException("World block does not satisfy the original rule for " + part.id());
    }
    if (part.field() != null) {
      var field = part.field();
      Long measured = value.fields().get(field.name());
      if (measured == null || measured < field.min() || measured > field.max()) {
        throw new IllegalArgumentException("World field does not satisfy the original rule for " + part.id());
      }
    }
    if (!settings.selector().isEmpty() && !settings.selector().equals(part.selector())
        || !settings.interfaceType().isEmpty() && !settings.interfaceType().equals(part.interfaceType())) {
      throw new IllegalArgumentException("Explicit selector/interface conflicts with fixed part " + part.id());
    }
  }

  private List<Captured> selection(Box box, boolean dynamic) {
    if (box.volume() > RangedSelectionData.MAX_CAPTURE) {
      throw new IllegalArgumentException("Selection exceeds the editor capture budget");
    }
    if (dynamic && contains(box, input.controller())) {
      throw new IllegalArgumentException("A dynamic selection cannot contain the controller");
    }
    var result = new ArrayList<Captured>();
    var first = box.first();
    var second = box.second();
    for (var position : BlockPos.betweenClosed(
        Math.min(first.getX(), second.getX()),
        Math.min(first.getY(), second.getY()),
        Math.min(first.getZ(), second.getZ()),
        Math.max(first.getX(), second.getX()),
        Math.max(first.getY(), second.getY()),
        Math.max(first.getZ(), second.getZ()))) {
      checkWork();
      var value = captured.get(position);
      if (value == null) {
        throw new IllegalArgumentException("World capture is incomplete at " + position.toShortString());
      }
      if (input.includeAir() || !value.state().isAir() || value.world().equals(input.controller())) {
        result.add(value);
      }
    }
    result.sort(ORDER);
    return result;
  }

  private Bounds bounds(Box box) {
    BlockPos min = null;
    BlockPos max = null;
    for (int x : new int[]{box.first().getX(), box.second().getX()}) {
      for (int y : new int[]{box.first().getY(), box.second().getY()}) {
        for (int z : new int[]{box.first().getZ(), box.second().getZ()}) {
          var corner = local(new BlockPos(x, y, z), input.controller(), input.orientation());
          min = min == null ? corner : new BlockPos(
              Math.min(min.getX(), corner.getX()),
              Math.min(min.getY(), corner.getY()),
              Math.min(min.getZ(), corner.getZ()));
          max = max == null ? corner : new BlockPos(
              Math.max(max.getX(), corner.getX()),
              Math.max(max.getY(), corner.getY()),
              Math.max(max.getZ(), corner.getZ()));
        }
      }
    }
    return new Bounds(min, max);
  }

  private static BlockPos step(Settings settings, Bounds bounds) {
    if (settings.customStep()) {
      return settings.step();
    }
    Direction direction = settings.direction();
    long length = switch (direction.getAxis()) {
      case X -> (long) bounds.max().getX() - bounds.min().getX() + 1;
      case Y -> (long) bounds.max().getY() - bounds.min().getY() + 1;
      case Z -> (long) bounds.max().getZ() - bounds.min().getZ() + 1;
    };
    length = Math.addExact(length, settings.gap());
    return new BlockPos(
        Math.toIntExact(length * direction.getStepX()),
        Math.toIntExact(length * direction.getStepY()),
        Math.toIntExact(length * direction.getStepZ()));
  }

  private static boolean contains(Box box, BlockPos position) {
    return position.getX() >= Math.min(box.first().getX(), box.second().getX())
        && position.getX() <= Math.max(box.first().getX(), box.second().getX())
        && position.getY() >= Math.min(box.first().getY(), box.second().getY())
        && position.getY() <= Math.max(box.first().getY(), box.second().getY())
        && position.getZ() >= Math.min(box.first().getZ(), box.second().getZ())
        && position.getZ() <= Math.max(box.first().getZ(), box.second().getZ());
  }

  private static BlockPos local(BlockPos world, BlockPos controller, MachineOrientation orientation) {
    long x = (long) world.getX() - controller.getX();
    long y = (long) world.getY() - controller.getY();
    long z = (long) world.getZ() - controller.getZ();
    return new BlockPos(
        dot(x, y, z, orientation.right()),
        dot(x, y, z, orientation.up()),
        dot(x, y, z, orientation.back()));
  }

  private static int dot(long x, long y, long z, Direction axis) {
    return Math.toIntExact(x * axis.getStepX() + y * axis.getStepY() + z * axis.getStepZ());
  }

  private static BlockPos relative(BlockPos value, BlockPos base, BlockPos step, int repetition) {
    return new BlockPos(
        Math.toIntExact((long) value.getX() - base.getX() - (long) step.getX() * repetition),
        Math.toIntExact((long) value.getY() - base.getY() - (long) step.getY() * repetition),
        Math.toIntExact((long) value.getZ() - base.getZ() - (long) step.getZ() * repetition));
  }

  private static BlockPos vector(JsonElement value) {
    if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() != 3) {
      throw new IllegalArgumentException("Expected a three-component part offset");
    }
    var values = value.getAsJsonArray();
    return new BlockPos(
        values.get(0).getAsBigDecimal().intValueExact(),
        values.get(1).getAsBigDecimal().intValueExact(),
        values.get(2).getAsBigDecimal().intValueExact());
  }

  private static JsonArray coordinates(BlockPos position) {
    var result = new JsonArray();
    result.add(position.getX());
    result.add(position.getY());
    result.add(position.getZ());
    return result;
  }

  private static int arraySize(JsonObject object, String primary, String legacy) {
    if (object.has(primary)) {
      return object.getAsJsonArray(primary).size();
    }
    return object.has(legacy) ? object.getAsJsonArray(legacy).size() : 0;
  }

  private static JsonObject canonicalPattern(JsonObject source) {
    var result = source.deepCopy();
    for (var keys : List.of(
        List.of("id", "name"),
        List.of("minimum", "minSize"),
        List.of("maximum", "maxSize"),
        List.of("start_offset", "structure-size-offset-start"),
        List.of("step", "structure-size-offset"),
        List.of("parts_start", "parts-start"),
        List.of("parts_end", "parts-end"))) {
      if (result.has(keys.get(1))) {
        result.add(keys.getFirst(), result.remove(keys.get(1)));
      }
    }
    if (!result.has("minimum")) {
      result.addProperty("minimum", 0);
    }
    if (!result.has("start_offset")) {
      result.add("start_offset", coordinates(BlockPos.ZERO));
    }
    for (String key : List.of("parts_start", "parts_end")) {
      if (result.has(key) && result.getAsJsonArray(key).isEmpty()) {
        result.remove(key);
      }
    }
    return result;
  }

  private void checkWork() {
    if (++work > 2 * RangedSelectionData.MAX_CAPTURE) {
      throw new IllegalArgumentException("Combined selections exceed the editor work budget");
    }
    if ((work & 4095) == 0 && Thread.currentThread().isInterrupted()) {
      throw new CancellationException();
    }
  }
}
