package com.lw.modularmachine.integration.kubejs;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.lw.modularmachine.machine.recipe.MachineRecipeJson;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/**
 * Captures KubeJS machine declarations so the native strict catalog can
 * validate them.
 */
public final class MachineItemRecipeKubeJSSources {
  private static Map<ResourceLocation, JsonElement> current = Map.of();

  private MachineItemRecipeKubeJSSources() {
  }

  public static synchronized void capture(Map<ResourceLocation, JsonElement> recipes) {
    var next = new LinkedHashMap<ResourceLocation, JsonElement>();
    recipes.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
      var root = entry.getValue();
      if (!root.isJsonObject()
          || !root.getAsJsonObject().has("type")
          || !MachineRecipeJson.TYPE.equals(root.getAsJsonObject().get("type").getAsString())) {
        return;
      }
      if (!entry.getKey().getNamespace().equals("kubejs")) {
        return;
      }
      MachineRecipeJson.validate(root);
      next.put(entry.getKey(), root.deepCopy());
      if (next.size() > 16_384) {
        throw new JsonParseException("Too many KubeJS machine recipes");
      }
    });
    current = Map.copyOf(next);
  }

  public static synchronized Map<ResourceLocation, JsonElement> snapshot() {
    var copy = new LinkedHashMap<ResourceLocation, JsonElement>();
    current.forEach((id, root) -> copy.put(id, root.deepCopy()));
    return Map.copyOf(copy);
  }

  public static synchronized void clear() {
    current = Map.of();
  }
}
