package com.lw.modularmachine.client.selection;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Design;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.ApiStatus;

/** Internal declarations frozen with a completed client preview compilation. */
@ApiStatus.Internal
public final class RangedSelectionExportSnapshot {
  private final List<JsonObject> fixed;
  private final List<Pattern> patterns;

  static final class Pattern {
    private final String key, id;
    private final BlockPos start, step;
    private final List<JsonObject> head, body, tail;

    Pattern(String key, String id, BlockPos start, BlockPos step, List<JsonObject> head, List<JsonObject> body, List<JsonObject> tail) {
      this.key = key;
      this.id = id;
      this.start = start.immutable();
      this.step = step.immutable();
      this.head = List.copyOf(head);
      this.body = List.copyOf(body);
      this.tail = List.copyOf(tail);
    }
  }

  RangedSelectionExportSnapshot(List<JsonObject> fixed, List<Pattern> patterns) {
    // The compiler transfers its exclusive declarations after its final step.
    // Copying JSON is deferred to root(), which may run off the client thread.
    this.fixed = List.copyOf(fixed);
    this.patterns = List.copyOf(patterns);
  }

  /**
   * Returns detached JSON using the current machine flags, color and displayed
   * counts. An inherited color is omitted so the loader uses the global default.
   * Geometry, IDs and part settings remain those of this completed snapshot.
   */
  public JsonObject root(Design current, String exportName) {
    var maximums = new HashMap<String, Integer>();
    for (int index = 0; index < current.keys().size(); index++) {
      maximums.put(current.keys().get(index), current.segments().get(index).settings().maximum());
    }
    var usedIds = new HashSet<String>();
    for (var pattern : patterns) {
      if (!pattern.id.isEmpty() && !usedIds.add(pattern.id)) {
        throw new IllegalArgumentException("Duplicate dynamic segment ID: " + pattern.id);
      }
    }
    var root = new JsonObject();
    root.addProperty("translation_key", "machine.mmcece." + exportName.replace('/', '.'));
    root.addProperty("requires_blueprint", current.requiresBlueprint());
    root.addProperty("has_factory", current.hasFactory());
    root.addProperty("factory_only", current.factoryOnly());
    if (!current.color().isEmpty()) {
      root.addProperty("color", current.color());
    }
    var orientation = new JsonObject();
    orientation.addProperty("policy", "all_24");
    root.add("orientation", orientation);
    root.add("parts", parts(fixed));
    var dynamic = new JsonArray();
    int suffix = 1;
    for (var pattern : patterns) {
      Integer maximum = maximums.get(pattern.key);
      if (maximum == null) {
        throw new IllegalArgumentException("The preview no longer contains dynamic segment " + pattern.key);
      }
      String id = pattern.id;
      if (id.isEmpty()) {
        while (usedIds.contains("segment_" + suffix)) {
          suffix++;
        }
        id = "segment_" + suffix++;
        usedIds.add(id);
      }
      var declaration = new JsonObject();
      declaration.addProperty("id", id);
      declaration.addProperty("minimum", 1);
      declaration.addProperty("maximum", maximum);
      declaration.add("start_offset", coordinates(pattern.start));
      declaration.add("step", coordinates(pattern.step));
      declaration.add("parts", parts(pattern.body));
      if (!pattern.head.isEmpty()) {
        declaration.add("parts_start", parts(pattern.head));
      }
      if (!pattern.tail.isEmpty()) {
        declaration.add("parts_end", parts(pattern.tail));
      }
      dynamic.add(declaration);
    }
    if (!dynamic.isEmpty()) {
      root.add("dynamic_patterns", dynamic);
    }

    return root;
  }

  private static JsonArray parts(List<JsonObject> source) {
    var result = new JsonArray();
    source.forEach(part -> result.add(part.deepCopy()));

    return result;
  }

  static JsonArray coordinates(BlockPos position) {
    var result = new JsonArray();
    result.add(position.getX());
    result.add(position.getY());
    result.add(position.getZ());

    return result;
  }
}
