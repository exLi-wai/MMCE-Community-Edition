package com.lw.modularmachine.machine.recipe.adapter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterImport;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/** Internal, immutable import settings captured with a machine definition. */
@ApiStatus.Internal
public final class RecipeAdapterDefinitions {
  public static final int MAX_IMPORTS = 64;
  private static final Set<String> FIELDS = Set
      .of("id", "adapter", "recipes", "duration", "energy_per_tick", "priority");

  private RecipeAdapterDefinitions() {
  }

  public static List<RecipeAdapterImport> parse(JsonObject root, String path) {
    if (!root.has("recipe_adapters")) {
      return List.of();
    }
    String at = path + ".recipe_adapters";
    var element = root.get("recipe_adapters");
    if (!element.isJsonArray() || element.getAsJsonArray().size() > MAX_IMPORTS) {
      throw error(at, "Expected at most " + MAX_IMPORTS + " recipe adapter imports");
    }
    var values = element.getAsJsonArray();
    var imports = new ArrayList<RecipeAdapterImport>(values.size());
    var importIds = new HashSet<String>();
    for (int index = 0; index < values.size(); index++) {
      String importPath = at + "[" + index + "]";
      if (!values.get(index).isJsonObject()) {
        throw error(importPath, "Expected an adapter import object");
      }
      var row = values.get(index).getAsJsonObject();
      if (!FIELDS.containsAll(row.keySet())) {
        throw error(importPath, "Unknown recipe adapter import field");
      }
      String id = string(row.get("id"), importPath + ".id");
      if (!id.matches("[a-zA-Z0-9_.-]{1,64}")) {
        throw error(importPath + ".id", "Expected 1..64 letters, digits, underscores, dots or hyphens");
      }
      if (!importIds.add(id)) {
        throw error(importPath + ".id", "Duplicate recipe adapter import ID " + id);
      }
      var adapter = identifier(row.get("adapter"), importPath + ".adapter");
      if (!RecipeAdapterRegistry.known(adapter)) {
        throw error(importPath + ".adapter", "Unknown recipe adapter " + adapter);
      }
      boolean allRecipes = !row.has("recipes");
      var recipes = new ArrayList<ResourceLocation>();
      if (!allRecipes) {
        var selected = row.get("recipes");
        if (!selected.isJsonArray()) {
          throw error(importPath + ".recipes", "Expected a recipe ID array");
        }
        var seen = new HashSet<ResourceLocation>();
        for (int recipeIndex = 0; recipeIndex < selected.getAsJsonArray().size(); recipeIndex++) {
          var recipe = identifier(
              selected.getAsJsonArray().get(recipeIndex),
              importPath + ".recipes[" + recipeIndex + "]");
          if (!seen.add(recipe)) {
            throw error(importPath + ".recipes", "Duplicate source recipe ID " + recipe);
          }
          recipes.add(recipe);
        }
      }
      int duration = 0;
      if (row.has("duration")) {
        duration = integer(row.get("duration"), importPath + ".duration", 1, Integer.MAX_VALUE);
      }
      long energyPerTick = 0;
      if (row.has("energy_per_tick")) {
        energyPerTick = nonnegativeLong(row.get("energy_per_tick"), importPath + ".energy_per_tick");
      }
      int priority = 0;
      if (row.has("priority")) {
        priority = integer(row.get("priority"), importPath + ".priority", Integer.MIN_VALUE, Integer.MAX_VALUE);
      }
      imports.add(new RecipeAdapterImport(id, adapter, recipes, allRecipes, duration, energyPerTick, priority));
    }
    validate(imports);

    return List.copyOf(imports);
  }

  public static void validate(List<RecipeAdapterImport> imports) {
    if (imports.size() > MAX_IMPORTS) {
      throw new IllegalArgumentException("Too many machine recipe adapter imports");
    }
    var ids = new HashSet<String>();
    for (var entry : imports) {
      if (!ids.add(entry.id())) {
        throw new IllegalArgumentException("Duplicate machine recipe adapter import ID " + entry.id());
      }
    }
  }

  private static ResourceLocation identifier(JsonElement element, String path) {
    var id = ResourceLocation.tryParse(string(element, path));
    if (id == null) {
      throw error(path, "Expected a valid resource location");
    }

    return id;
  }

  private static String string(JsonElement element, String path) {
    if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
      throw error(path, "Expected a string");
    }

    return element.getAsString();
  }

  private static int integer(JsonElement element, String path, int minimum, int maximum) {
    long value = integralLong(element, path);
    if (value < minimum || value > maximum) {
      throw error(path, "Expected an integer in " + minimum + ".." + maximum);
    }

    return (int) value;
  }

  private static long nonnegativeLong(JsonElement element, String path) {
    long value = integralLong(element, path);
    if (value < 0) {
      throw error(path, "Expected a nonnegative long value");
    }

    return value;
  }

  private static long integralLong(JsonElement element, String path) {
    if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
      throw error(path, "Expected an integer number");
    }
    try {
      return element.getAsBigDecimal().longValueExact();
    } catch (ArithmeticException | NumberFormatException invalid) {
      throw error(path, "Expected an integer in long range");
    }
  }

  private static JsonParseException error(String path, String message) {
    return new JsonParseException(path + ": " + message);
  }
}
