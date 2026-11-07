package com.lw.modularmachine.api.smart;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.lw.modularmachine.machine.recipe.ResourceSelector;
import com.lw.modularmachine.machine.smart.SmartInterfaceType;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Start-only read-only numerical admission; no amount is consumed. */
public record SmartNumberRequirement(String type, double minimum, double maximum) {
  public static final Codec<SmartNumberRequirement> CODEC = RecordCodecBuilder.create(
      instance -> instance
          .group(
              Codec.STRING.fieldOf("type").forGetter(SmartNumberRequirement::type),
              Codec.DOUBLE.fieldOf("min").forGetter(SmartNumberRequirement::minimum),
              Codec.DOUBLE.fieldOf("max").forGetter(SmartNumberRequirement::maximum))
          .apply(instance, SmartNumberRequirement::new));

  public SmartNumberRequirement {
    ResourceSelector.require(type);
    if (type.isEmpty() || !Double.isFinite(minimum) || !Double.isFinite(maximum) || minimum > maximum) {
      throw new IllegalArgumentException("Invalid smart interface number requirement");
    }
  }

  public boolean matches(double value) {
    return Double.isFinite(value) && value >= minimum && value <= maximum;
  }

  public static List<SmartNumberRequirement> parse(JsonElement source, String path) {
    if (source == null || !source.isJsonArray() || source.getAsJsonArray().size() > SmartInterfaceType.MAX_TYPES) {
      throw new JsonParseException(path + ": Expected a bounded number requirement array");
    }
    var result = new ArrayList<SmartNumberRequirement>();
    for (var entry : source.getAsJsonArray()) {
      if (!entry.isJsonObject() || !entry.getAsJsonObject().keySet().equals(Set.of("type", "min", "max"))) {
        throw new JsonParseException(path + ": Expected type/min/max fields");
      }
      var root = entry.getAsJsonObject();
      var type = root.get("type");
      if (!type.isJsonPrimitive() || !type.getAsJsonPrimitive().isString()) {
        throw new JsonParseException(path + ".type: Expected string");
      }
      result.add(
          new SmartNumberRequirement(
              type.getAsString(),
              SmartInterfaceType.finite(root.get("min"), path + ".min"),
              SmartInterfaceType.finite(root.get("max"), path + ".max")));
    }

    return List.copyOf(result);
  }
}
