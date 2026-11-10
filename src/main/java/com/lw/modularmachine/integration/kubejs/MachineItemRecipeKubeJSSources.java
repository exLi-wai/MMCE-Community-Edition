package com.lw.modularmachine.integration.kubejs;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.lw.modularmachine.machine.recipe.MachineRecipeJson;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/**
 * Captures the live KubeJS recipe map so the native strict catalog can validate
 * script-added machine recipes.
 *
 * <p>
 * KubeJS calls the plugin hook before its recipe event and mutates the same map
 * in place afterwards, so the reference must not be copied here. The snapshot
 * is taken after the recipe manager has applied its changes.
 */
public final class MachineItemRecipeKubeJSSources {
  private static final int MAX_RECIPES = 16_384;
  private static Map<ResourceLocation, JsonElement> captured = Map.of();
  private static Map<ResourceLocation, JsonElement> originalMachineRecipes = Map.of();

  private MachineItemRecipeKubeJSSources() {
  }

  public static synchronized void capture(Map<ResourceLocation, JsonElement> recipes) {
    captured = recipes;
    var originals = new LinkedHashMap<ResourceLocation, JsonElement>();
    for (var entry : recipes.entrySet()) {
      if (isMachineRecipe(entry.getValue())) {
        originals.put(entry.getKey(), normalizeKubeJsJson(entry.getValue().getAsJsonObject()));
      }
    }
    originalMachineRecipes = Map.copyOf(originals);
  }

  /**
   * Deep-copies and validates the KubeJS machine rows currently in the captured
   * map. Only new or changed declarations are script sources; unchanged originals
   * retain their native directory validation. IDs have no namespace restriction.
   * The returned JSON is the normalized native shape, not the raw script output.
   */
  public static synchronized Map<ResourceLocation, JsonElement> snapshot() {
    var result = new LinkedHashMap<ResourceLocation, JsonElement>();
    for (var entry : captured.entrySet()) {
      var root = entry.getValue();
      if (!isMachineRecipe(root)) {
        continue;
      }
      var normalized = normalizeKubeJsJson(root.getAsJsonObject());
      if (normalized.equals(originalMachineRecipes.get(entry.getKey()))) {
        continue;
      }
      MachineRecipeJson.validate(normalized);
      result.put(entry.getKey(), normalized);
      if (result.size() > MAX_RECIPES) {
        throw new JsonParseException("Too many KubeJS machine recipes");
      }
    }

    return Map.copyOf(result);
  }

  public static synchronized void clear() {
    captured = Map.of();
    originalMachineRecipes = Map.of();
  }

  private static boolean isMachineRecipe(JsonElement root) {
    if (!root.isJsonObject()) {
      return false;
    }
    var type = root.getAsJsonObject().get("type");

    return type != null
        && type.isJsonPrimitive()
        && type.getAsJsonPrimitive().isString()
        && MachineRecipeJson.TYPE.equals(type.getAsString());
  }

  /**
   * Drops the KubeJS-internal change marker and normalizes item stack prototypes.
   * KubeJS writes {@code count: 1} in the vanilla prototype form; native machine
   * rows keep the amount separately, so the redundant count is removed while any
   * other count is left for validation to reject.
   */
  private static JsonObject normalizeKubeJsJson(JsonObject root) {
    var copy = root.deepCopy();
    copy.remove(MachineRecipeJson.KUBEJS_CHANGED_MARKER);
    for (var field : List.of("inputs", "outputs")) {
      var rows = copy.get(field);
      if (rows == null || !rows.isJsonArray()) {
        continue;
      }
      for (var row : rows.getAsJsonArray()) {
        if (!row.isJsonObject()) {
          continue;
        }
        var stack = row.getAsJsonObject().get("stack");
        if (stack == null || !stack.isJsonObject()) {
          continue;
        }
        var prototype = stack.getAsJsonObject();
        var count = prototype.get("count");
        if (count == null || !count.isJsonPrimitive() || !count.getAsJsonPrimitive().isNumber()) {
          continue;
        }
        try {
          if (count.getAsBigDecimal().compareTo(BigDecimal.ONE) == 0) {
            prototype.remove("count");
          }
        } catch (NumberFormatException ignored) {
          // Leave the malformed value for MachineRecipeJson.validate to report.
        }
      }
    }

    return copy;
  }
}
