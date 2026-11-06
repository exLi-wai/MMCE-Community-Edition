package com.lw.modularmachine.machine.structure;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Machine variable files expanded into the canonical block field. */
final class MachineDefinitionVariables {
  private static final int MAX_VARIABLES = 4096;
  private static final int MAX_VALUES = 256;
  private final Map<String, List<String>> values;

  private MachineDefinitionVariables(Map<String, List<String>> values) {
    this.values = Map.copyOf(values);
  }

  static MachineDefinitionVariables from(Map<String, List<String>> values) {
    return new MachineDefinitionVariables(values);
  }

  Map<String, List<String>> values() {
    return values;
  }

  static MachineDefinitionVariables parse(JsonObject root, String source) {
    if (root.size() > MAX_VARIABLES) {
      throw new JsonParseException(source + ": too many variables");
    }
    var result = new LinkedHashMap<String, List<String>>();
    for (var entry : root.entrySet()) {
      if (entry.getKey().isBlank() || result.containsKey(entry.getKey())) {
        throw new JsonParseException(source + ": duplicate or blank variable " + entry.getKey());
      }
      var values = new ArrayList<String>();
      JsonElement value = entry.getValue();
      if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
        values.add(value.getAsString());
      } else if (value.isJsonArray()) {
        JsonArray array = value.getAsJsonArray();
        if (array.isEmpty() || array.size() > MAX_VALUES) {
          throw new JsonParseException(source + ": variable " + entry.getKey() + " must contain 1..256 values");
        }
        for (JsonElement element : array) {
          if (!(element instanceof JsonPrimitive primitive) || !primitive.isString()) {
            throw new JsonParseException(source + ": variable " + entry.getKey() + " contains a non-string value");
          }
          values.add(primitive.getAsString());
        }
      } else {
        throw new JsonParseException(source + ": variable " + entry.getKey() + " must be a string or string array");
      }
      if (values.stream().anyMatch(String::isBlank)) {
        throw new JsonParseException(source + ": variable " + entry.getKey() + " contains a blank descriptor");
      }
      result.put(entry.getKey(), List.copyOf(values));
    }
    return new MachineDefinitionVariables(result);
  }

  JsonObject expandParts(JsonObject root, String source) {
    if (!root.has("parts") || !root.get("parts").isJsonArray()) {
      return root;
    }
    JsonObject copy = root.deepCopy();
    var parts = copy.getAsJsonArray("parts");
    for (int index = 0; index < parts.size(); index++) {
      if (!parts.get(index).isJsonObject()) {
        continue;
      }
      JsonObject part = parts.get(index).getAsJsonObject();
      if (!part.has("block")) {
        continue;
      }
      JsonElement block = part.get("block");
      if (block.isJsonPrimitive() && block.getAsJsonPrimitive().isString() && values.containsKey(block.getAsString())) {
        part.add("block", expandCandidates(block, source + ": $.parts[" + index + "].block"));
      } else if (block.isJsonArray()) {
        part.add("block", expandCandidates(block, source + ": $.parts[" + index + "].block"));
      } else if (!block.isJsonPrimitive() || !block.getAsJsonPrimitive().isString()) {
        throw new JsonParseException(source + ": $.parts[" + index + "].block must be a string or string array");
      }
    }
    return copy;
  }

  private JsonArray expandCandidates(JsonElement value, String path) {
    var result = new JsonArray();
    if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
      addReference(result, value.getAsString(), path);
      return result;
    }
    if (!value.isJsonArray() || value.getAsJsonArray().isEmpty() || value.getAsJsonArray().size() > MAX_VALUES) {
      throw new JsonParseException(path + ": expected a string or 1..256 string candidates");
    }
    for (var element : value.getAsJsonArray()) {
      if (!(element instanceof JsonPrimitive primitive) || !primitive.isString()) {
        throw new JsonParseException(path + ": block candidates must contain strings");
      }
      addReference(result, primitive.getAsString(), path);
    }
    return result;
  }

  private void addReference(JsonArray target, String reference, String path) {
    List<String> expanded = values.get(reference);
    if (expanded == null) {
      target.add(reference);
      return;
    }
    for (String value : expanded) {
      if (values.containsKey(value)) {
        throw new JsonParseException(path + ": nested variable reference " + value);
      }
      target.add(value);
    }
  }

}
