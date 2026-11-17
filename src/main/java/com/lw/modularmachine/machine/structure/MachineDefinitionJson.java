package com.lw.modularmachine.machine.structure;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.util.Set;
import org.jetbrains.annotations.ApiStatus;
/** Shared machine-declaration JSON primitives with precise source paths. */
@ApiStatus.Internal
final class MachineDefinitionJson {
  private MachineDefinitionJson() {
  }

  static JsonObject object(JsonElement value, String path) {
    if (value == null || !value.isJsonObject()) {
      throw error(path, "Expected object");
    }
    return value.getAsJsonObject();
  }

  static JsonArray array(JsonElement value, String path) {
    if (value == null || !value.isJsonArray()) {
      throw error(path, "Expected array");
    }
    return value.getAsJsonArray();
  }

  static String string(JsonElement value, String path) {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
      throw error(path, "Expected string");
    }
    return value.getAsString();
  }

  static String literal(JsonElement value, String path) {
    if (value == null || !value.isJsonPrimitive()) {
      throw error(path, "Expected property literal");
    }
    return value.getAsString();
  }

  static long optionalInteger(JsonObject owner, String name, long fallback, String path) {
    return owner.has(name) ? integer(owner.get(name), path + "." + name) : fallback;
  }

  static long integer(JsonElement value, String path) {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
      throw error(path, "Expected integer");
    }
    try {
      return value.getAsBigDecimal().longValueExact();
    } catch (ArithmeticException | NumberFormatException exception) {
      throw error(path, "Non-integral/out-of-range integer");
    }
  }

  static void fields(JsonObject object, String path, String... allowed) {
    Set<String> names = Set.of(allowed);
    for (String name : object.keySet()) {
      if (!names.contains(name)) {
        throw error(path + "." + name, "Unsupported field");
      }
    }
  }

  static JsonParseException error(String path, String message) {
    return new JsonParseException(path + ": " + message);
  }
}
