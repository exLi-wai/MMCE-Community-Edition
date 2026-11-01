package com.lw.modularmachine.machine.structure;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.lw.modularmachine.machine.orientation.OrientationCompilation;
import com.lw.modularmachine.machine.orientation.StateOrientationAdapters;
import com.lw.modularmachine.machine.recipe.RecipeModifier;
import com.lw.modularmachine.machine.recipe.RecipeModifiers;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/**
 * One optional all-of condition group; alternatives remain part of the normal
 * structure proof.
 */
public record StructureModifierDefinition(
    String id,
    List<Integer> partIndices,
    List<MachineDefinition.Part> conditions,
    OrientationCompilation compilation,
    boolean allowReplacement,
    List<RecipeModifier> modifiers) {
  public static final int MAX_GROUPS = 256;
  public static final int MAX_CONDITIONS = 4096;
  private static final Set<String> FIELDS = Set.of("id", "parts", "modifiers", "allow_replacement");
  private static final Set<String> PART_FIELDS = Set.of("part_id", "block", "blocks", "properties", "properties_space");

  public StructureModifierDefinition {
    partIndices = List.copyOf(partIndices);
    conditions = List.copyOf(conditions);
    modifiers = List.copyOf(modifiers);
    if (id == null
        || !id.matches("[a-zA-Z0-9_.-]{1,64}")
        || partIndices.isEmpty()
        || partIndices.size() > 256
        || partIndices.stream().anyMatch(index -> index < 0)
        || new HashSet<>(partIndices).size() != partIndices.size()
        || partIndices.size() != conditions.size()
        || conditions.stream().anyMatch(part -> part.rule().port() != null || part.field() != null)
        || compilation == null) {
      throw new IllegalArgumentException("Invalid structure modifier group");
    }
    new RecipeModifiers(modifiers);
  }

  private record Source(String id, JsonArray parts, JsonArray modifiers, boolean replace) {
  }

  public static List<StructureModifierDefinition> parse(ResourceLocation id, JsonObject root, List<MachineDefinition.Part> parts, StateOrientationAdapters.RegistrySnapshot snapshot) {
    var indices = new HashMap<String, Integer>();
    for (int index = 0; index < parts.size(); index++) {
      indices.put(parts.get(index).id(), index);
    }
    var result = new ArrayList<StructureModifierDefinition>();
    var allRules = new ArrayList<RecipeModifier>();
    for (var source : sources(root, id + ": $")) {
      var synthetic = new JsonObject();
      synthetic.addProperty("schema_version", 2);
      synthetic.add("parts", source.parts().deepCopy());
      if (root.has("orientation")) {
        synthetic.add("orientation", root.get("orientation").deepCopy());
      }
      final MachineDefinition compiled;
      try {
        compiled = MachineDefinition.parse(id, synthetic, snapshot);
      } catch (RuntimeException invalid) {
        throw new JsonParseException(
            id + ": $.structure_modifiers." + source.id() + ": " + invalid.getMessage(),
            invalid);
      }
      var rules = RecipeModifiers
          .parse(source.modifiers(), id + ": $.structure_modifiers." + source.id() + ".modifiers");
      allRules.addAll(rules);
      var references = compiled.parts().stream().map(part -> indices.get(part.id())).toList();
      result.add(
          new StructureModifierDefinition(
              source.id(),
              references,
              compiled.parts(),
              compiled.orientationCompilation(),
              source.replace(),
              rules));
    }
    new RecipeModifiers(allRules);

    return List.copyOf(result);
  }

  /**
   * Reuse the ordinary block/property descriptor capture before any registry-free
   * parsing.
   */
  static List<JsonObject> captureParts(JsonObject root, String path) {
    var result = new ArrayList<JsonObject>();
    for (var source : sources(root, path)) {
      for (var part : source.parts()) {
        result.add(part.getAsJsonObject());
      }
    }

    return result;
  }

  private static List<Source> sources(JsonObject root, String path) {
    if (!root.has("structure_modifiers")) {
      return List.of();
    }
    String at = path + ".structure_modifiers";
    if (root.get("schema_version").getAsInt() != 2 || !root.get("structure_modifiers").isJsonArray()) {
      throw new JsonParseException(at + ": Requires machine schema 2 and an array");
    }
    var groups = root.getAsJsonArray("structure_modifiers");
    if (groups.size() > MAX_GROUPS) {
      throw new JsonParseException(at + ": Too many structure modifier groups");
    }
    var original = new HashMap<String, JsonObject>();
    for (var part : root.getAsJsonArray("parts")) {
      if (part.isJsonObject() && part.getAsJsonObject().has("part_id")) {
        var object = part.getAsJsonObject();
        original.put(text(object, "part_id", path + ".parts"), object);
      }
    }
    var names = new HashSet<String>();
    var result = new ArrayList<Source>();
    int total = 0;
    for (int index = 0; index < groups.size(); index++) {
      String groupPath = at + "[" + index + "]";
      if (!groups.get(index).isJsonObject()) {
        throw new JsonParseException(groupPath + ": Expected object");
      }
      var group = groups.get(index).getAsJsonObject();
      String name = text(group, "id", groupPath);
      if (!FIELDS.containsAll(group.keySet())
          || !name.matches("[a-zA-Z0-9_.-]{1,64}")
          || !names.add(name)
          || !group.has("parts")
          || !group.get("parts").isJsonArray()
          || !group.has("modifiers")
          || !group.get("modifiers").isJsonArray()) {
        throw new JsonParseException(groupPath + ": Invalid/duplicate group ID, fields, parts or modifiers");
      }
      boolean replace = true;
      if (group.has("allow_replacement")) {
        var value = group.get("allow_replacement");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
          throw new JsonParseException(groupPath + ".allow_replacement: Expected boolean");
        }
        replace = value.getAsBoolean();
      }
      var conditions = group.getAsJsonArray("parts");
      total += conditions.size();
      if (conditions.isEmpty() || conditions.size() > 256 || total > MAX_CONDITIONS) {
        throw new JsonParseException(groupPath + ".parts: Structure condition limit exceeded");
      }
      var normalized = new JsonArray();
      var seen = new HashSet<String>();
      for (var value : conditions) {
        if (!value.isJsonObject()) {
          throw new JsonParseException(groupPath + ".parts: Expected condition object");
        }
        var condition = value.getAsJsonObject();
        String ref = text(condition, "part_id", groupPath + ".parts");
        var target = original.get(ref);
        if (!PART_FIELDS.containsAll(condition.keySet())
            || !seen.add(ref)
            || target == null
            || target.has("port")
            || target.has("field")
            || target.has("interface")) {
          throw new JsonParseException(
              groupPath + ".parts: Conditions require distinct explicit non-port/non-field part IDs");
        }
        var part = condition.deepCopy();
        part.add("offset", target.get("offset").deepCopy());
        if (!part.has("properties_space") && target.has("properties_space")) {
          part.add("properties_space", target.get("properties_space").deepCopy());
        }
        normalized.add(part);
      }
      result.add(new Source(name, normalized, group.getAsJsonArray("modifiers"), replace));
    }

    return result;
  }

  private static String text(JsonObject root, String field, String path) {
    var value = root.get(field);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
      throw new JsonParseException(path + "." + field + ": Expected string");
    }

    return value.getAsString();
  }
}
