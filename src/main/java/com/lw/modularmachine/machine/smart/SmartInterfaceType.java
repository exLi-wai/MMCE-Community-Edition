package com.lw.modularmachine.machine.smart;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.lw.modularmachine.machine.recipe.ResourceSelector;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Immutable machine-owned numerical type. UI text is translation data, never
 * executable formatting.
 */
public record SmartInterfaceType(
    String id,
    double defaultValue,
    double minimum,
    double maximum,
    String header,
    String valueLabel,
    String footer,
    int priority) {
  public static final int MAX_TYPES = 64;
  private static final Set<String> FIELDS = Set
      .of("id", "default", "min", "max", "header", "value_label", "footer", "priority");

  public SmartInterfaceType(String id, double defaultValue, double minimum, double maximum, String header, String valueLabel, String footer) {
    this(id, defaultValue, minimum, maximum, header, valueLabel, footer, 0);
  }

  public SmartInterfaceType {
    ResourceSelector.require(id);
    if (id.isEmpty()
        || !Double.isFinite(defaultValue)
        || !Double.isFinite(minimum)
        || !Double.isFinite(maximum)
        || minimum > maximum
        || defaultValue < minimum
        || defaultValue > maximum
        || header == null
        || valueLabel == null
        || footer == null
        || header.length() > 256
        || valueLabel.length() > 256
        || footer.length() > 256) {
      throw new IllegalArgumentException("Invalid numerical smart interface type");
    }
  }

  public boolean accepts(double value) {
    return Double.isFinite(value) && value >= minimum && value <= maximum;
  }

  public static Map<String, SmartInterfaceType> parse(JsonElement source, String path) {
    if (source == null || !source.isJsonArray() || source.getAsJsonArray().size() > MAX_TYPES) {
      throw new JsonParseException(path + ": Expected at most " + MAX_TYPES + " interface types");
    }
    var result = new LinkedHashMap<String, SmartInterfaceType>();
    for (var element : source.getAsJsonArray()) {
      if (!element.isJsonObject() || !FIELDS.containsAll(element.getAsJsonObject().keySet())) {
        throw new JsonParseException(path + ": Invalid smart interface fields");
      }
      var root = element.getAsJsonObject();
      var type = new SmartInterfaceType(
          text(root, "id", ""),
          number(root, "default", 0),
          number(root, "min", -Double.MAX_VALUE),
          number(root, "max", Double.MAX_VALUE),
          text(root, "header", ""),
          text(root, "value_label", ""),
          text(root, "footer", ""),
          priority(root, path + ".priority"));
      if (result.putIfAbsent(type.id(), type) != null) {
        throw new JsonParseException(path + ": Duplicate interface type " + type.id());
      }
    }

    return Map.copyOf(result);
  }

  private static String text(JsonObject root, String field, String fallback) {
    if (!root.has(field)) {
      return fallback;
    }
    var value = root.get(field);
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
      throw new JsonParseException(field + ": Expected string");
    }

    return value.getAsString();
  }

  private static int priority(JsonObject root, String path) {
    if (!root.has("priority")) {
      return 0;
    }
    var value = root.get("priority");
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
      throw new JsonParseException(path + ": Expected signed integer priority");
    }
    try {
      return value.getAsBigDecimal().intValueExact();
    } catch (ArithmeticException exception) {
      throw new JsonParseException(path + ": Priority is outside the signed integer range", exception);
    }
  }

  private static double number(JsonObject root, String field, double fallback) {
    if (!root.has(field)) {
      return fallback;
    }

    return finite(root.get(field), field);
  }

  public static double finite(JsonElement source, String path) {
    if (source == null || !source.isJsonPrimitive() || !source.getAsJsonPrimitive().isNumber()) {
      throw new JsonParseException(path + ": Expected finite number");
    }
    var exact = source.getAsBigDecimal();
    double value = exact.doubleValue();
    if (!Double.isFinite(value) || value == 0 && exact.signum() != 0) {
      throw new JsonParseException(path + ": Number overflow or underflow");
    }

    return value;
  }
}
