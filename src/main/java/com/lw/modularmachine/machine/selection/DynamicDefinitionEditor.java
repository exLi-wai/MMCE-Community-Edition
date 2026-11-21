package com.lw.modularmachine.machine.selection;

import com.lw.modularmachine.machine.structure.MachineDefinitionParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.orientation.StateOrientationAdapters;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Box;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Segment;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Settings;
import com.lw.modularmachine.machine.selection.RangedSelectionData.PartEdit;
import com.lw.modularmachine.machine.structure.DynamicLayoutValidator;
import com.lw.modularmachine.machine.structure.DynamicPatternDefinition;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;
import static com.lw.modularmachine.machine.selection.SelectionGeometry.local;
import static com.lw.modularmachine.machine.selection.SelectionGeometry.relative;
import static com.lw.modularmachine.machine.selection.SelectionGeometry.step;

/**
 * Internal pure editor; registry descriptors and world values are captured on
 * the server thread.
 */
@ApiStatus.Internal
public final class DynamicDefinitionEditor {
  public record Captured(BlockPos world, BlockState state, JsonObject part) {
    public Captured {
      world = Objects.requireNonNull(world).immutable();
      Objects.requireNonNull(state);
      part = Objects.requireNonNull(part).deepCopy();
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
      boolean includeAir,
      List<Captured> captured,
      Map<BlockPos, PartEdit> edits) {
    public Input {
      Objects.requireNonNull(machine);
      original = original == null ? null : original.deepCopy();
      Objects.requireNonNull(variables);
      Objects.requireNonNull(orientation);
      controller = Objects.requireNonNull(controller).immutable();
      segments = List.copyOf(segments);
      captured = List.copyOf(captured);
      var frozen = new LinkedHashMap<BlockPos, PartEdit>();
      edits.forEach((position, edit) -> frozen.put(position.immutable(), edit));
      edits = Map.copyOf(frozen);
      if (segments.size() > RangedSelectionData.MAX_SEGMENTS
          || captured.size() > RangedSelectionData.MAX_CAPTURE
          || edits.size() > RangedSelectionData.MAX_PART_EDITS) {
        throw new IllegalArgumentException("Selection exceeds the editor capture budget");
      }
    }
  }

  public record Result(
      JsonObject root,
      MachineDefinition definition,
      int mainParts,
      int dynamicParts,
      int excludedController,
      List<String> segmentIds) {
    public Result {
      segmentIds = List.copyOf(segmentIds);
    }
  }

  private static final Comparator<Captured> ORDER = Comparator
      .comparingInt((Captured value) -> vector(value.part().get("offset")).getY())
      .thenComparingInt(value -> vector(value.part().get("offset")).getX())
      .thenComparingInt(value -> vector(value.part().get("offset")).getZ());

  private final Input input;
  private final Map<BlockPos, Captured> captured = new HashMap<>();
  private final Set<BlockPos> assigned = new HashSet<>();
  private final Set<BlockPos> appliedEdits = new HashSet<>();
  private int dynamicParts;
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
          MachineDefinitionParser.blockReferences(
              Map.of(input.machine(), input.variables().expand(input.original(), input.machine().toString()))));
    }
    if (!input.captured().isEmpty()) {
      var root = new JsonObject();
      var parts = new JsonArray();
      // The reference reader does not interpret offsets or part IDs.
      var declarations = new HashSet<JsonObject>();
      for (var value : input.captured()) {
        var part = PartOverrides.apply(value.part(), input.edits().get(value.world()));
        part.remove("offset");
        if (declarations.add(part)) {
          var declaration = part.deepCopy();
          declaration.add("offset", coordinates(BlockPos.ZERO));
          parts.add(declaration);
        }
      }
      root.add("parts", parts);
      references.addAll(
          MachineDefinitionParser
              .blockReferences(Map.of(input.machine(), input.variables().expand(root, input.machine().toString()))));
    }
    return StateOrientationAdapters.distinctReferences(references);
  }

  public static Result edit(Input input, StateOrientationAdapters.RegistrySnapshot registry) {
    return new DynamicDefinitionEditor(input).edit(registry);
  }

  /**
   * Appends detached segment declarations to a copy of the target and checks
   * every permitted length.
   */
  public static JsonObject appendSegments(JsonObject target, JsonArray additions) {
    if (additions.isEmpty()) {
      throw new IllegalArgumentException("Select a dynamic repeating body before appending to a machine");
    }
    String key = target.has("dynamic-patterns") ? "dynamic-patterns" : "dynamic_patterns";
    var merged = target.deepCopy();
    var patterns = merged.has(key) ? merged.getAsJsonArray(key) : new JsonArray();
    patterns.addAll(additions);
    merged.add(key, patterns);
    DynamicLayoutValidator.validate(merged);
    return merged;
  }

  /**
   * Every selected template part must survive the loader's optional-block
   * filtering.
   */
  public static void requireLoadedSegments(MachineDefinition definition, List<DynamicPatternDefinition.Declaration> selected) {
    var loaded = new HashMap<String, DynamicPatternDefinition>();
    definition.dynamicPatterns().forEach(pattern -> loaded.put(pattern.id(), pattern));
    for (var declaration : selected) {
      var pattern = loaded.get(declaration.id());
      if (pattern == null
          || pattern.start().size() != declaration.start().size()
          || pattern.body().size() != declaration.body().size()
          || pattern.end().size() != declaration.end().size()) {
        throw new IllegalArgumentException(
            "Dynamic segment " + declaration.id() + " contains unavailable block constraints");
      }
    }
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
    JsonArray parts;
    int excludedController = 0;
    if (input.original() == null) {
      parts = new JsonArray();
      for (var value : selection(input.main(), false)) {
        if (value.world().equals(input.controller())) {
          excludedController++;
          continue;
        }
        if (assigned.contains(value.world())) {
          throw new IllegalArgumentException(
              "Main body overlaps a dynamic selection at " + value.world().toShortString());
        }
        parts.add(edited(value.part(), value.world()));
      }
      root.add("parts", parts);
    } else {
      parts = root.getAsJsonArray("parts");
    }
    if (parts.isEmpty()) {
      throw new IllegalArgumentException("At least one fixed part besides the controller is required");
    }
    if (!appliedEdits.containsAll(input.edits().keySet())) {
      throw RangedSelectionMessages.invalid("unused_part_edits");
    }
    var definition = MachineDefinitionParser
        .parse(input.machine(), input.variables().expand(root, input.machine().toString()), registry);
    if (!definition.allows(input.orientation())) {
      throw new IllegalArgumentException(
          "The complete definition cannot represent the selected controller orientation");
    }
    if (definition.parts().size() != parts.size()) {
      throw new IllegalArgumentException("The fixed definition contains unavailable block constraints");
    }
    requireLoadedSegments(definition, DynamicPatternDefinition.declarations(root, input.machine() + ": $"));
    DynamicLayoutValidator.validate(definition);
    return new Result(root, definition, parts.size(), dynamicParts, excludedController, segmentIds);
  }

  private JsonObject pattern(Segment segment, String id) {
    var settings = segment.settings();
    var bounds = SelectionGeometry.bounds(segment.body(), input.controller(), input.orientation());
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
      var part = value.part();
      if (!settings.selector().isEmpty() || !settings.interfaceType().isEmpty()) {
        part = part.deepCopy();
        if (!settings.selector().isEmpty()) {
          part.addProperty("selector", settings.selector());
        }
        if (!settings.interfaceType().isEmpty()) {
          part.addProperty("interface", settings.interfaceType());
        }
      }
      part = edited(part, value.world());
      part.add("offset", coordinates(relative(absolute, base, step, repetition)));
      result.add(part);
      dynamicParts++;
    }
    return result;
  }

  private JsonObject edited(JsonObject original, BlockPos position) {
    var edit = input.edits().get(position);
    if (edit != null) {
      appliedEdits.add(position);
    }
    return PartOverrides.apply(original, edit);
  }

  private List<Captured> selection(Box box, boolean dynamic) {
    if (box.volume() > RangedSelectionData.MAX_CAPTURE) {
      throw new IllegalArgumentException("Selection exceeds the editor capture budget");
    }
    if (dynamic && box.contains(input.controller())) {
      throw new IllegalArgumentException("A dynamic selection cannot contain the controller");
    }
    var result = new ArrayList<Captured>();
    for (var position : box.positions()) {
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
