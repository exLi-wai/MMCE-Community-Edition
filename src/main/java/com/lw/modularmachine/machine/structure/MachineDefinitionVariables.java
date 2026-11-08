package com.lw.modularmachine.machine.structure;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Machine variable files expanded into the canonical block field. */
final class MachineDefinitionVariables {
  private static final int MAX_VARIABLES = 4096;
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
    long totalValues = 0;
    for (var entry : root.entrySet()) {
      if (entry.getKey().isBlank() || result.containsKey(entry.getKey())) {
        throw new JsonParseException(source + ": duplicate or blank variable " + entry.getKey());
      }
      var values = new LinkedHashSet<String>();
      JsonElement value = entry.getValue();
      if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
        values.add(value.getAsString());
      } else if (value.isJsonArray()) {
        JsonArray array = value.getAsJsonArray();
        if (array.isEmpty()) {
          throw new JsonParseException(source + ": variable " + entry.getKey() + " must contain at least one value");
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
      totalValues += values.size();
      if (totalValues > MachineDefinition.ALTERNATIVE_RESOURCE_BUDGET) {
        throw new JsonParseException(source + ": variable values exceed the shared alternative resource budget");
      }
      result.put(entry.getKey(), List.copyOf(values));
    }
    return new MachineDefinitionVariables(result);
  }

  JsonObject expandParts(JsonObject root, String source) {
    JsonObject copy = root.deepCopy();
    var budget = new ExpansionBudget();
    if (copy.has("parts") && copy.get("parts").isJsonArray()) {
      expandParts(copy.getAsJsonArray("parts"), source + ": $.parts", budget);
    }
    var patterns = DynamicPatternDefinition.sources(copy, source + ": $");
    for (int patternIndex = 0; patternIndex < patterns.size(); patternIndex++) {
      var pattern = patterns.get(patternIndex);
      for (String key : List.of("parts_start", "parts-start", "parts", "parts_end", "parts-end")) {
        if (pattern.has(key) && pattern.get(key).isJsonArray()) {
          expandParts(
              pattern.getAsJsonArray(key),
              source + ": $.dynamic_patterns[" + patternIndex + "]." + key,
              budget);
        }
      }
    }
    return copy;
  }

  private void expandParts(JsonArray parts, String path, ExpansionBudget budget) {
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
        part.add("block", expandCandidates(block, path + "[" + index + "].block", budget));
      } else if (block.isJsonArray()) {
        part.add("block", expandCandidates(block, path + "[" + index + "].block", budget));
      } else if (!block.isJsonPrimitive() || !block.getAsJsonPrimitive().isString()) {
        throw new JsonParseException(path + "[" + index + "].block must be a string or string array");
      } else {
        budget.take(path);
      }
    }
  }

  private JsonArray expandCandidates(JsonElement value, String path, ExpansionBudget budget) {
    var candidates = new LinkedHashSet<String>();
    if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
      addReference(candidates, value.getAsString(), path, budget);
    } else {
      if (!value.isJsonArray() || value.getAsJsonArray().isEmpty()) {
        throw new JsonParseException(path + ": expected a string or a nonempty string candidate array");
      }
      var references = new LinkedHashSet<String>();
      for (var element : value.getAsJsonArray()) {
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isString()) {
          throw new JsonParseException(path + ": block candidates must contain strings");
        }
        if (references.add(primitive.getAsString())) {
          addReference(candidates, primitive.getAsString(), path, budget);
        }
      }
    }
    var result = new JsonArray();
    candidates.forEach(result::add);
    return result;
  }

  private void addReference(Set<String> target, String reference, String path, ExpansionBudget budget) {
    List<String> expanded = values.get(reference);
    if (expanded == null) {
      addCandidate(target, reference, path, budget);
      return;
    }
    for (String value : expanded) {
      if (values.containsKey(value)) {
        throw new JsonParseException(path + ": nested variable reference " + value);
      }
      addCandidate(target, value, path, budget);
    }
  }

  private static void addCandidate(Set<String> target, String value, String path, ExpansionBudget budget) {
    if (target.add(value)) {
      budget.take(path);
    }
  }

  private static final class ExpansionBudget {
    private long remaining = MachineDefinition.ALTERNATIVE_RESOURCE_BUDGET;

    void take(String path) {
      if (--remaining < 0) {
        throw new JsonParseException(path + ": variable expansion exceeds the shared alternative resource budget");
      }
    }
  }

}
