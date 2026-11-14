package com.lw.modularmachine.machine.selection;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lw.modularmachine.machine.selection.RangedSelectionData.PartEdit;
import java.util.LinkedHashSet;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal whitelist-only edits to detached declarations, never to world or
 * block-entity data.
 */
@ApiStatus.Internal
public final class PartOverrides {
  private PartOverrides() {
  }

  public static JsonObject apply(JsonObject original, PartEdit edit) {
    var result = original.deepCopy();
    if (edit == null) {
      return result;
    }
    if (!edit.blocks().isEmpty()) {
      var names = new LinkedHashSet<String>();
      for (String name : edit.blocks().split(",", -1)) {
        if (name.isBlank()) {
          throw RangedSelectionMessages.invalid("empty_candidate");
        }
        names.add(name.strip());
      }
      if (names.size() == 1) {
        result.addProperty("block", names.iterator().next());
      } else {
        var array = new JsonArray();
        names.forEach(array::add);
        result.add("block", array);
      }
    }
    if (!edit.properties().isEmpty()) {
      var properties = new JsonObject();
      if (!edit.properties().equals("-")) {
        for (String assignment : edit.properties().split(";", -1)) {
          var parts = assignment.split("=", 2);
          if (parts.length != 2 || !parts[0].strip().matches("[a-zA-Z0-9_]+") || properties.has(parts[0].strip())) {
            throw RangedSelectionMessages.invalid("invalid_property_assignment");
          }
          var values = new LinkedHashSet<String>();
          for (String value : parts[1].split("\\|", -1)) {
            if (value.isBlank()) {
              throw RangedSelectionMessages.invalid("empty_property_value");
            }
            values.add(value.strip());
          }
          if (values.size() == 1) {
            properties.addProperty(parts[0].strip(), values.iterator().next());
          } else {
            var array = new JsonArray();
            values.forEach(array::add);
            properties.add(parts[0].strip(), array);
          }
        }
      }
      if (properties.isEmpty()) {
        result.remove("properties");
        result.remove("properties_space");
      } else {
        result.add("properties", properties);
        result.addProperty("properties_space", edit.propertiesSpace());
      }
    }
    if (edit.changeSelector()) {
      setOrRemove(result, "selector", edit.selector());
    }
    if (edit.changeInterface()) {
      setOrRemove(result, "interface", edit.interfaceType());
    }
    return result;
  }

  private static void setOrRemove(JsonObject target, String key, String value) {
    if (value.isEmpty()) {
      target.remove(key);
    } else {
      target.addProperty(key, value);
    }
  }
}
