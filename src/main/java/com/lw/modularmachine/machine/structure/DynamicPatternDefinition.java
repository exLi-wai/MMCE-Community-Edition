package com.lw.modularmachine.machine.structure;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.lw.modularmachine.machine.orientation.OrientationCompilation;
import com.lw.modularmachine.machine.orientation.OrientationPolicy;
import com.lw.modularmachine.machine.orientation.StateOrientationAdapters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal immutable local-space templates; only resolved layouts can produce.
 */
@ApiStatus.Internal
public record DynamicPatternDefinition(
    String id,
    int minimum,
    int maximum,
    BlockPos startOffset,
    BlockPos step,
    List<MachineDefinition.Part> start,
    List<MachineDefinition.Part> body,
    List<MachineDefinition.Part> end,
    OrientationCompilation startCompilation,
    OrientationCompilation bodyCompilation,
    OrientationCompilation endCompilation) {
  public static final int MAX_PATTERNS = 64;
  public static final int MAX_EXPANDED_PARTS = 4_194_304;
  private static final Set<String> FIELDS = Set.of(
      "id",
      "name",
      "minimum",
      "minSize",
      "maximum",
      "maxSize",
      "start_offset",
      "structure-size-offset-start",
      "step",
      "structure-size-offset",
      "parts_start",
      "parts-start",
      "parts",
      "parts_end",
      "parts-end",
      "faces");

  public DynamicPatternDefinition {
    startOffset = startOffset.immutable();
    step = step.immutable();
    start = List.copyOf(start);
    body = List.copyOf(body);
    end = List.copyOf(end);
    if (id == null
        || !id.matches("[a-zA-Z0-9_.-]{1,64}")
        || minimum < 0
        || maximum < minimum
        || step.equals(BlockPos.ZERO)
        || body.isEmpty()
        || bodyCompilation == null
        || !start.isEmpty() && startCompilation == null
        || !end.isEmpty() && endCompilation == null) {
      throw new IllegalArgumentException("Invalid dynamic structure template");
    }
    if ((long) start.size() + (long) maximum * body.size() + end.size() > MAX_EXPANDED_PARTS) {
      throw new IllegalArgumentException("Dynamic structure exceeds 4194304 expanded parts");
    }
    // Both endpoints fit: all intermediate affine offsets fit as well.
    for (var part : start) {
      translated(part.offset(), startOffset, step, 0);
    }
    for (var part : body) {
      translated(part.offset(), startOffset, step, 0);
      translated(part.offset(), startOffset, step, maximum);
    }
    for (var part : end) {
      translated(part.offset(), startOffset, step, minimum);
      translated(part.offset(), startOffset, step, maximum);
    }
  }

  public int allowedOrientationMask() {
    int mask = bodyCompilation.allowedMask();
    if (startCompilation != null) {
      mask &= startCompilation.allowedMask();
    }
    if (endCompilation != null) {
      mask &= endCompilation.allowedMask();
    }
    return mask;
  }

  public List<MachineDefinition.Part> declarationParts() {
    var result = new ArrayList<MachineDefinition.Part>(start.size() + body.size() + end.size());
    result.addAll(start);
    result.addAll(body);
    result.addAll(end);
    return List.copyOf(result);
  }

  public static BlockPos translated(BlockPos local, BlockPos start, BlockPos step, int repetition) {
    return new BlockPos(
        Math.toIntExact((long) local.getX() + start.getX() + (long) step.getX() * repetition),
        Math.toIntExact((long) local.getY() + start.getY() + (long) step.getY() * repetition),
        Math.toIntExact((long) local.getZ() + start.getZ() + (long) step.getZ() * repetition));
  }

  static List<DynamicPatternDefinition> parse(ResourceLocation machine, JsonObject root, OrientationPolicy policy, StateOrientationAdapters.RegistrySnapshot snapshot) {
    var result = new ArrayList<DynamicPatternDefinition>();
    var ids = new HashSet<String>();
    var sources = sources(root, machine + ": $");
    for (int index = 0; index < sources.size(); index++) {
      var source = sources.get(index);
      String path = machine + ": $.dynamic_patterns[" + index + "]";
      var identifier = alias(source, "id", "name", path);
      if (identifier == null || !identifier.isJsonPrimitive() || !identifier.getAsJsonPrimitive().isString()) {
        throw error(path + ".id", "Expected pattern ID");
      }
      String id = identifier.getAsString();
      if (!id.matches("[a-zA-Z0-9_.-]{1,64}") || !ids.add(id)) {
        throw error(path + ".id", "Expected unique pattern ID of 1..64 letters, digits, _, - or .");
      }
      int minimum = integer(alias(source, "minimum", "minSize", path), 0, path + ".minimum");
      int maximum = integer(alias(source, "maximum", "maxSize", path), -1, path + ".maximum");
      var offset = vector(
          alias(source, "start_offset", "structure-size-offset-start", path),
          BlockPos.ZERO,
          path + ".start_offset");
      var step = vector(alias(source, "step", "structure-size-offset", path), null, path + ".step");
      if (source.has("faces")) {
        validateFaces(source.get("faces"), path + ".faces");
      }
      var start = MachineDefinition.parseParts(
          machine,
          partArray(alias(source, "parts_start", "parts-start", path), false, path + ".parts_start"),
          path + ".parts_start",
          snapshot,
          true);
      var body = MachineDefinition
          .parseParts(machine, partArray(source.get("parts"), true, path + ".parts"), path + ".parts", snapshot, true);
      var end = MachineDefinition.parseParts(
          machine,
          partArray(alias(source, "parts_end", "parts-end", path), false, path + ".parts_end"),
          path + ".parts_end",
          snapshot,
          true);
      try {
        result.add(
            new DynamicPatternDefinition(
                id,
                minimum,
                maximum,
                offset,
                step,
                start,
                body,
                end,
                start.isEmpty() ? null : OrientationCompilation.compile(machine, policy, start),
                OrientationCompilation.compile(machine, policy, body),
                end.isEmpty() ? null : OrientationCompilation.compile(machine, policy, end)));
      } catch (RuntimeException invalid) {
        throw new JsonParseException(path + ": " + invalid.getMessage(), invalid);
      }
    }
    return List.copyOf(result);
  }

  static List<JsonElement> captureParts(JsonObject root, String path) {
    var result = new ArrayList<JsonElement>();
    for (var source : sources(root, path)) {
      partArray(alias(source, "parts_start", "parts-start", path), false, path + ".parts_start").forEach(result::add);
      partArray(source.get("parts"), true, path + ".parts").forEach(result::add);
      partArray(alias(source, "parts_end", "parts-end", path), false, path + ".parts_end").forEach(result::add);
    }
    return result;
  }

  static List<JsonObject> sources(JsonObject root, String path) {
    var value = alias(root, "dynamic_patterns", "dynamic-patterns", path);
    if (value == null) {
      return List.of();
    }
    if (!value.isJsonArray() || value.getAsJsonArray().isEmpty() || value.getAsJsonArray().size() > MAX_PATTERNS) {
      throw error(path + ".dynamic_patterns", "Expected 1..64 dynamic patterns");
    }
    var result = new ArrayList<JsonObject>();
    for (var element : value.getAsJsonArray()) {
      if (!element.isJsonObject() || !FIELDS.containsAll(element.getAsJsonObject().keySet())) {
        throw error(path + ".dynamic_patterns", "Invalid pattern or unsupported field");
      }
      result.add(element.getAsJsonObject());
    }
    return result;
  }

  private static JsonArray partArray(JsonElement value, boolean required, String path) {
    if (value == null && !required) {
      return new JsonArray();
    }
    if (value == null || !value.isJsonArray() || required && value.getAsJsonArray().isEmpty()) {
      throw error(path, "Expected " + (required ? "nonempty " : "") + "part array");
    }
    return value.getAsJsonArray();
  }

  private static JsonElement alias(JsonObject object, String primary, String legacy, String path) {
    if (object.has(primary) && object.has(legacy)) {
      throw error(path, "Use only one of " + primary + " or " + legacy);
    }
    return object.has(primary) ? object.get(primary) : object.get(legacy);
  }

  private static int integer(JsonElement value, int fallback, String path) {
    if (value == null) {
      return fallback;
    }
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
      throw error(path, "Expected integer");
    }
    try {
      return value.getAsBigDecimal().intValueExact();
    } catch (ArithmeticException invalid) {
      throw error(path, "Expected 32-bit integer");
    }
  }

  private static BlockPos vector(JsonElement value, BlockPos fallback, String path) {
    if (value == null) {
      if (fallback == null) {
        throw error(path, "Required nonzero local step");
      }
      return fallback;
    }
    if (value.isJsonArray() && value.getAsJsonArray().size() == 3) {
      var array = value.getAsJsonArray();
      return new BlockPos(
          integer(array.get(0), 0, path),
          integer(array.get(1), 0, path),
          integer(array.get(2), 0, path));
    }
    if (value.isJsonObject() && value.getAsJsonObject().keySet().equals(Set.of("x", "y", "z"))) {
      var object = value.getAsJsonObject();
      return new BlockPos(
          integer(object.get("x"), 0, path),
          integer(object.get("y"), 0, path),
          integer(object.get("z"), 0, path));
    }
    throw error(path, "Expected three integer coordinates");
  }

  private static void validateFaces(JsonElement value, String path) {
    if (!value.isJsonArray() || value.getAsJsonArray().isEmpty()) {
      throw error(path, "Expected nonempty legacy direction list");
    }
    for (var face : value.getAsJsonArray()) {
      if (!face.isJsonPrimitive()
          || !face.getAsJsonPrimitive().isString()
          || Direction.byName(face.getAsString().toLowerCase(java.util.Locale.ROOT)) == null) {
        throw error(path, "Unknown direction");
      }
    }
  }

  private static JsonParseException error(String path, String message) {
    return new JsonParseException(path + ": " + message);
  }
}
