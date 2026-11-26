package com.lw.modularmachine.integration.kubejs;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.machine.recipe.MachineRecipeJson;
import com.lw.modularmachine.machine.recipe.RecipeScriptSources;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeManager;
import org.jetbrains.annotations.ApiStatus;

/**
 * Captures the live KubeJS recipe map so the native strict catalog can validate
 * script-added machine recipes.
 *
 * <p>
 * KubeJS calls the plugin hook before its recipe event and mutates the same map
 * in place afterwards, so the reference must not be copied here. The snapshot
 * is taken after the recipe manager has applied its changes.
 */
@ApiStatus.Internal
public final class MachineItemRecipeKubeJSSources {
  private static final int MAX_RECIPES = 16_384;

  /** A winning script addition and the surviving recipe it shadowed, if any. */
  public record Replacement(JsonElement added, JsonElement surviving) {
  }

  /** Implemented on each KubeJS recipe event by its mixin. */
  public interface Replacements {
    Replacement MMCECE$replacement(ResourceLocation id);
  }

  private MachineItemRecipeKubeJSSources() {
  }

  /** Both maps use KubeJS's final IDs and already exclude removed recipes. */
  public static Map<ResourceLocation, Replacement> machineReplacements(Map<ResourceLocation, JsonElement> surviving, Map<ResourceLocation, JsonElement> additions) {
    var replacements = new LinkedHashMap<ResourceLocation, Replacement>();
    additions.forEach((id, added) -> {
      var replaced = surviving.get(id);
      if (isMachineRecipe(added)) {
        replacements.put(id, new Replacement(added, replaced));
      }
    });
    return replacements;
  }

  static void capture(RecipeManager manager, Map<ResourceLocation, JsonElement> recipes, Function<ResourceLocation, Replacement> replacementAt) {
    var originals = new LinkedHashMap<ResourceLocation, JsonElement>();
    for (var entry : recipes.entrySet()) {
      if (isMachineRecipe(entry.getValue())) {
        originals.put(entry.getKey(), entry.getValue().deepCopy());
      }
    }
    var originalMachineRecipes = Map.copyOf(originals);
    RecipeScriptSources.capture(
        manager,
        recipes::get,
        generation -> snapshot(recipes, originalMachineRecipes, replacementAt, generation));
  }

  /**
   * Deep-copies and validates the KubeJS machine rows currently in the captured
   * map. Only new or changed declarations are script sources; unchanged originals
   * retain their native directory validation. IDs have no namespace restriction.
   * Skipped IDs let the catalog exclude the corresponding prospective holders.
   */
  private static RecipeScriptSources.Snapshot snapshot(Map<ResourceLocation, JsonElement> recipes, Map<ResourceLocation, JsonElement> originals, Function<ResourceLocation, Replacement> replacementAt, long callbackGeneration) {
    var result = new LinkedHashMap<ResourceLocation, JsonElement>();
    var skipped = new LinkedHashSet<ResourceLocation>();
    var skippedMachines = new LinkedHashMap<ResourceLocation, ResourceLocation>();
    var ordinaryFallbacks = new LinkedHashMap<ResourceLocation, JsonElement>();
    var removedNativeIds = new LinkedHashSet<>(originals.keySet());
    var correctedStackPrototypes = new LinkedHashSet<ResourceLocation>();
    int overflow = 0;
    var machineRecipes = recipes.entrySet().stream().filter(entry -> isMachineRecipe(entry.getValue()))
        .sorted(Map.Entry.comparingByKey()).toList();
    for (var entry : machineRecipes) {
      var id = entry.getKey();
      // RecipeManager.apply ignores these IDs before any codec can run.
      if (id.getPath().startsWith("_")) {
        continue;
      }
      try {
        var root = entry.getValue().getAsJsonObject();
        var original = originals.get(id);
        var replacement = replacementAt.apply(id);
        if (replacement != null && replacement.added() != root) {
          replacement = null;
        }
        if (replacement == null || isMachineRecipe(replacement.surviving())) {
          removedNativeIds.remove(id);
        }
        // Before normalization count:1 is evidence of a native error. An explicit
        // script replacement can also have equal JSON.
        if (replacement == null && root.equals(original)) {
          continue;
        }
        // KubeJS overwrites the holder by ID even if the replacement fails decoding.
        if (replacement != null && replacement.surviving() != null && !isMachineRecipe(replacement.surviving())) {
          ordinaryFallbacks.put(id, replacement.surviving().deepCopy());
        }
        if (result.size() >= MAX_RECIPES) {
          skipped.add(id);
          overflow++;
          continue;
        }
        var normalized = root.deepCopy();
        normalized.remove(MachineRecipeJson.KUBEJS_CHANGED_MARKER);
        boolean corrected = normalizeStackPrototypes(normalized);
        MachineRecipeJson.validate(normalized);
        result.put(id, normalized);
        if (corrected) {
          correctedStackPrototypes.add(id);
        }
      } catch (RuntimeException invalid) {
        skipped.add(id);
        ModularMachine.LOGGER.warn("Skipping invalid KubeJS machine recipe {}", id, invalid);
      }
    }
    if (overflow > 0) {
      ModularMachine.LOGGER.warn("Skipped {} KubeJS machine recipes above the limit of {}", overflow, MAX_RECIPES);
    }

    for (var id : skipped) {
      var machine = MachineRecipeJson.declaredMachine(recipes.get(id));
      if (machine != null) {
        skippedMachines.put(id, machine);
      }
    }

    // RecipeManager.apply visits this final map in encounter order, including IDs
    // it fails to decode.
    var recipeOrder = ordinaryFallbacks.isEmpty() ? List.<ResourceLocation>of() : List.copyOf(recipes.keySet());
    return new RecipeScriptSources.Snapshot(
        result,
        removedNativeIds,
        correctedStackPrototypes,
        skipped,
        skippedMachines,
        ordinaryFallbacks,
        recipeOrder,
        callbackGeneration);
  }

  private static boolean isMachineRecipe(JsonElement root) {
    if (root == null || !root.isJsonObject()) {
      return false;
    }
    var type = root.getAsJsonObject().get("type");

    return type != null
        && type.isJsonPrimitive()
        && type.getAsJsonPrimitive().isString()
        && MachineRecipeJson.TYPE.equals(type.getAsString());
  }

  /**
   * KubeJS writes {@code count: 1} in the vanilla prototype form; native machine
   * rows keep the amount separately, so the redundant count is removed while any
   * other count is left for validation to reject.
   */
  private static boolean normalizeStackPrototypes(JsonObject copy) {
    boolean corrected = false;
    for (var field : List.of("inputs", "outputs")) {
      var rows = copy.get(field);
      if (rows == null || !rows.isJsonArray()) {
        continue;
      }
      for (var row : rows.getAsJsonArray()) {
        if (!row.isJsonObject()) {
          continue;
        }
        for (String prototypeField : List.of("stack", "preview_stack")) {
          var stack = row.getAsJsonObject().get(prototypeField);
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
              corrected = true;
            }
          } catch (NumberFormatException ignored) {
            // Leave the malformed value for MachineRecipeJson.validate to report.
          }
        }
      }
    }

    return corrected;
  }
}
