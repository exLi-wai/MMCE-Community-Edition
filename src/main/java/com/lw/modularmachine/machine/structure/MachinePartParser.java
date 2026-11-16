package com.lw.modularmachine.machine.structure;

import com.lw.modularmachine.api.component.ResourceType;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.orientation.PropertySpace;
import com.lw.modularmachine.machine.orientation.StateOrientationAdapters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import net.minecraft.world.level.block.state.properties.Property;
import com.lw.modularmachine.machine.structure.MachineDefinition.Part;
import com.lw.modularmachine.machine.structure.MachineDefinition.Rule;
import com.lw.modularmachine.machine.structure.MachineDefinition.Alternative;
import com.lw.modularmachine.machine.structure.MachineDefinition.FieldCondition;
import static com.lw.modularmachine.machine.structure.MachineDefinitionJson.*;

/**
 * Part declarations, bounded alternatives and property expansion from captured
 * registry values.
 */
@org.jetbrains.annotations.ApiStatus.Internal
final class MachinePartParser {
  static final long ALTERNATIVE_RESOURCE_BUDGET = 4_194_304L;
  private static final Logger LOGGER = LogUtils.getLogger();
  private MachinePartParser() {
  }

  static List<Part> parse(ResourceLocation id, JsonArray elements, String path, StateOrientationAdapters.RegistrySnapshot snapshot, boolean allowAnchor) {
    List<Part> parts = new ArrayList<>();
    var alternativeBudget = new AlternativeBudget();
    int skippedParts = 0;
    Set<BlockPos> offsets = new HashSet<>();
    Set<String> partIds = new HashSet<>();
    for (int index = 0; index < elements.size(); index++) {
      String partPath = path + "[" + index + "]";
      JsonObject part = object(elements.get(index), partPath);
      partFields(part, partPath);
      JsonArray xyz = array(part.get("offset"), partPath + ".offset");
      if (xyz.size() != 3) {
        throw error(partPath + ".offset", "Expected three integer coordinates");
      }
      int[] values = new int[3];
      for (int axis = 0; axis < 3; axis++) {
        long value = integer(xyz.get(axis), partPath + ".offset[" + axis + "]");
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
          throw error(partPath + ".offset[" + axis + "]", "Offset outside the 32-bit coordinate range");
        }
        values[axis] = (int) value;
      }
      BlockPos offset = new BlockPos(values[0], values[1], values[2]);
      if (!allowAnchor && offset.equals(BlockPos.ZERO) || !offsets.add(offset)) {
        throw error(partPath + ".offset", "Duplicate/controller-anchor part");
      }
      String partId = part.has("part_id") ? string(
          part.get("part_id"),
          partPath + ".part_id") : "offset:" + values[0] + "," + values[1] + "," + values[2];
      if (partId.isBlank() || partId.length() > 128 || !partIds.add(partId)) {
        throw error(partPath + ".part_id", "Expected unique nonblank part ID of at most 128 characters");
      }
      PropertySpace space;
      try {
        space = part.has("properties_space") ? PropertySpace
            .parse(string(part.get("properties_space"), partPath + ".properties_space")) : PropertySpace.LOCAL;
      } catch (IllegalArgumentException exception) {
        throw error(partPath + ".properties_space", exception.getMessage());
      }
      if (!part.has("block")) {
        throw error(partPath, "Expected block");
      }
      PortKind port = candidatePort(part);
      List<Alternative> alternatives = new ArrayList<>();
      if (port != null) {
        if (part.has("properties") || part.has("properties_space")) {
          throw error(partPath, "Core ports do not have state properties");
        }
      } else {
        JsonObject properties = part
            .has("properties") ? object(part.get("properties"), partPath + ".properties") : new JsonObject();
        var options = propertyOptions(properties, partPath + ".properties");
        for (String name : blockNames(part, partPath)) {
          ResourceLocation blockId = blockId(name, partPath);
          if (!snapshot.has(blockId)) {
            LOGGER.debug("{}: skipping unknown block {}", partPath, blockId);
            continue;
          }
          var block = snapshot.block(blockId, partPath);
          var resolved = resolveProperties(block, options, partPath + ".properties");
          int count = alternativeBudget.add(1, resolved, partPath);
          for (var required : propertyVariants(resolved, count)) {
            alternatives.add(new Alternative(block, required));
          }
        }
        if (alternatives.isEmpty()) {
          offsets.remove(offset);
          partIds.remove(partId);
          skippedParts++;
          LOGGER.debug("{}: no loadable blocks left; skipping the whole part", partPath);
          continue;
        }
      }
      FieldCondition condition = null;
      if (part.has("field")) {
        if (port == null) {
          throw error(partPath + ".field", "BE fields require an explicitly typed core port");
        }
        JsonObject field = object(part.get("field"), partPath + ".field");
        fields(field, partPath + ".field", "name", "min", "max");
        String name = string(field.get("name"), partPath + ".field.name");
        ResourceType expected = switch (name) {
          case "energy_stored" -> ResourceType.ENERGY;
          case "fluid_amount" -> ResourceType.FLUID;
          case "item_count" -> ResourceType.ITEM;
          default -> throw error(partPath + ".field.name", "Unsupported BE field: " + name);
        };
        if (port.resource() != expected) {
          throw error(partPath + ".field", "BE field/port resource mismatch");
        }
        long min = optionalInteger(field, "min", 0, partPath + ".field");
        long max = optionalInteger(field, "max", Long.MAX_VALUE, partPath + ".field");
        if (min < 0 || max < min) {
          throw error(partPath + ".field", "Invalid BE field range");
        }
        condition = new FieldCondition(name, min, max);
      }
      String selector = part.has("selector") ? string(part.get("selector"), partPath + ".selector") : "";
      String interfaceType = part.has("interface") ? string(part.get("interface"), partPath + ".interface") : "";
      parts.add(new Part(partId, offset, new Rule(port, alternatives), condition, space, selector, interfaceType));
    }
    if (skippedParts > 0) {
      LOGGER.warn(
          "{}: skipped {} part(s) whose blocks are missing from the registry (definition loads without them)",
          id,
          skippedParts);
    }
    return List.copyOf(parts);
  }

  static void partFields(JsonObject part, String path) {
    fields(
        part,
        path,
        "offset",
        "block",
        "properties",
        "field",
        "part_id",
        "properties_space",
        "selector",
        "interface");
  }

  static List<String> blockNames(JsonObject part, String path) {
    if (part.get("block").isJsonPrimitive()) {
      return List.of(string(part.get("block"), path + ".block"));
    }
    JsonArray elements = array(part.get("block"), path + ".block");
    if (elements.isEmpty()) {
      throw error(path + ".block", "Expected at least one alternative");
    }
    var result = new LinkedHashSet<String>();
    for (int i = 0; i < elements.size(); i++) {
      result.add(string(elements.get(i), path + ".block[" + i + "]"));
    }
    return List.copyOf(result);
  }

  static Map<String, List<String>> propertyOptions(JsonObject properties, String path) {
    var result = new LinkedHashMap<String, List<String>>();
    for (var entry : properties.entrySet()) {
      String propertyPath = path + "." + entry.getKey();
      var values = new LinkedHashSet<String>();
      if (entry.getValue().isJsonArray()) {
        JsonArray array = entry.getValue().getAsJsonArray();
        if (array.isEmpty()) {
          throw error(propertyPath, "Expected at least one property value");
        }
        for (int index = 0; index < array.size(); index++) {
          values.add(literal(array.get(index), propertyPath + "[" + index + "]"));
        }
      } else {
        values.add(literal(entry.getValue(), propertyPath));
      }
      result.put(entry.getKey(), List.copyOf(values));
    }
    return result;
  }

  static Map<Property<?>, List<Comparable<?>>> resolveProperties(StateOrientationAdapters.BlockSnapshot block, Map<String, List<String>> options, String path) {
    var result = new LinkedHashMap<Property<?>, List<Comparable<?>>>();
    for (var entry : options.entrySet()) {
      String propertyPath = path + "." + entry.getKey();
      var property = block.property(entry.getKey(), propertyPath);
      var values = new LinkedHashSet<Comparable<?>>();
      for (var literal : entry.getValue()) {
        values.add(property.value(literal, propertyPath));
      }
      result.put(property.property(), List.copyOf(values));
    }
    return result;
  }

  /**
   * Only the current combination is materialized; declared property order is
   * preserved.
   */
  static Iterable<Map<Property<?>, Comparable<?>>> propertyVariants(Map<Property<?>, List<Comparable<?>>> options, int count) {
    var properties = List.copyOf(options.keySet());
    var values = List.copyOf(options.values());
    return () -> new Iterator<>() {
      private int cursor;

      @Override
      public boolean hasNext() {
        return cursor < count;
      }

      @Override
      public Map<Property<?>, Comparable<?>> next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        if ((cursor & 4095) == 0 && Thread.currentThread().isInterrupted()) {
          throw new CancellationException();
        }
        int combination = cursor++;
        var selected = new Comparable<?>[properties.size()];
        for (int index = properties.size() - 1; index >= 0; index--) {
          var choices = values.get(index);
          selected[index] = choices.get(combination % choices.size());
          combination /= choices.size();
        }
        var result = new LinkedHashMap<Property<?>, Comparable<?>>();
        for (int index = 0; index < properties.size(); index++) {
          result.put(properties.get(index), selected[index]);
        }
        return result;
      }
    };
  }

  /**
   * Shared reload/direct-parse resource budget, separate from per-position
   * candidate semantics.
   */
  static final class AlternativeBudget {
    private long used;

    int add(int blocks, Map<?, ? extends List<?>> options, String path) {
      long count = blocks;
      long remaining = ALTERNATIVE_RESOURCE_BUDGET - used;
      if (count > remaining) {
        throw error(path, "Reload exceeds 4194304 block alternatives");
      }
      for (var values : options.values()) {
        if (count > remaining / values.size()) {
          throw error(path, "Reload exceeds 4194304 block alternatives");
        }
        count *= values.size();
      }
      used += count;
      return Math.toIntExact(count);
    }
  }

  static void checkAlternativeBudget(Map<ResourceLocation, ? extends JsonElement> roots) {
    if (roots.size() > 1024) {
      throw error("$", "At most 1024 machine definitions per reload");
    }
    var budget = new AlternativeBudget();
    long totalParts = 0;
    for (var entry : roots.entrySet()) {
      String path = entry.getKey() + ": $";
      var root = object(entry.getValue(), path);
      MachineDefinitionParser.header(root, path);
      var declarations = new ArrayList<JsonElement>();
      MachineDefinitionParser.parts(root, path).forEach(declarations::add);
      declarations.addAll(StructureModifierDefinition.captureParts(root, path));
      declarations.addAll(DynamicPatternDefinition.captureParts(root, path));
      totalParts += declarations.size();
      if (totalParts > 4_194_304L) {
        throw error(path + ".parts", "Reload exceeds 4194304 total parts");
      }
      for (int index = 0; index < declarations.size(); index++) {
        String partPath = path + ".parts[" + index + "]";
        var part = object(declarations.get(index), partPath);
        if (!part.has("block") || candidatePort(part) != null) {
          continue;
        }
        var properties = part
            .has("properties") ? object(part.get("properties"), partPath + ".properties") : new JsonObject();
        budget.add(blockNames(part, partPath).size(), propertyOptions(properties, partPath + ".properties"), partPath);
      }
    }
  }

  static PortKind blockPort(JsonElement value) {
    if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
      return tryParsePort(value.getAsString());
    }
    if (value.isJsonArray() && value.getAsJsonArray().size() == 1) {
      JsonElement candidate = value.getAsJsonArray().get(0);
      if (candidate.isJsonPrimitive() && candidate.getAsJsonPrimitive().isString()) {
        return tryParsePort(candidate.getAsString());
      }
    }
    return null;
  }

  static PortKind candidatePort(JsonObject part) {
    return blockPort(part.get("block"));
  }

  static PortKind tryParsePort(String value) {
    for (var kind : PortKind.values()) {
      if (kind.name().equalsIgnoreCase(value) || kind.path().equalsIgnoreCase(value)) {
        return kind;
      }
    }
    return null;
  }

  static ResourceLocation blockId(String name, String path) {
    ResourceLocation id = ResourceLocation.tryParse(name);
    if (id == null) {
      throw error(path, "Invalid block ID: " + name);
    }
    return id;
  }

}
