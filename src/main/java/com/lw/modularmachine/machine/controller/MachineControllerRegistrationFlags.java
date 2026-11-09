package com.lw.modularmachine.machine.controller;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal declaration flags shared by startup registration and definition
 * parsing.
 */
@ApiStatus.Internal
public record MachineControllerRegistrationFlags(boolean hasFactory, boolean factoryOnly) {
  public static MachineControllerRegistrationFlags parse(JsonObject root, String path) {
    return new MachineControllerRegistrationFlags(
        booleanAlias(root, "has_factory", "has-factory", path),
        booleanAlias(root, "factory_only", "factory-only", path));
  }

  private static boolean booleanAlias(JsonObject root, String primary, String legacy, String path) {
    if (root.has(primary) && root.has(legacy)) {
      throw new JsonParseException(path + ": Use only one of " + primary + " or " + legacy);
    }
    String key = root.has(primary) ? primary : root.has(legacy) ? legacy : null;
    if (key == null) {
      return false;
    }
    JsonElement value = root.get(key);
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
      throw new JsonParseException(path + "." + key + ": Expected boolean");
    }

    return value.getAsBoolean();
  }
}
