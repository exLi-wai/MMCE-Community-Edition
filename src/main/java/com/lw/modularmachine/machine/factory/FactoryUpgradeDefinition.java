package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.api.upgrade.FactoryUpgradeStartCost;

import com.lw.modularmachine.api.upgrade.FactoryUpgradeEntry;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.lw.modularmachine.machine.recipe.ItemResourceKey;
import com.lw.modularmachine.machine.recipe.RecipeModifiers;
import com.mojang.serialization.JsonOps;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Fixed upgrade effect for one ordinary item; definitions never mutate stacks.
 */
public record FactoryUpgradeDefinition(
    ResourceLocation item,
    FactoryUpgradeEntry effect,
    FactoryUpgradeCompatibility compatibility,
    JsonObject components,
    ResourceLocation dynamicEffect,
    ResourceLocation lifecycleHandler,
    ResourceLocation recipeCheckHandler,
    FactoryUpgradeStartCost startCost,
    ResourceLocation startDataHandler,
    ResourceLocation startBusDataHandler,
    FactoryUpgradeRestrictions restrictions,
    FactoryUpgradeStartCost tickCost,
    ResourceLocation tickDataHandler,
    ResourceLocation finishDataHandler) {
  public static final int MAX_COMPONENTS = 64;
  private static final Set<String> FIELDS = Set.of(
      "schema_version",
      "item",
      "parallel_add",
      "speed_numerator",
      "speed_denominator",
      "energy_numerator",
      "energy_denominator",
      "fluid_numerator",
      "fluid_denominator",
      "compatible_machines",
      "incompatible_machines",
      "components",
      "dynamic_effect",
      "lifecycle_handler",
      "recipe_check_handler",
      "start_cost",
      "tick_cost",
      "tick_data_handler",
      "finish_data_handler",
      "start_data_handler",
      "start_bus_data_handler",
      "recipe_modifiers",
      "incompatible_upgrades",
      "exclusive_group",
      "max_count");

  public FactoryUpgradeDefinition(ResourceLocation item, FactoryUpgradeEntry effect, FactoryUpgradeCompatibility compatibility, JsonObject components, ResourceLocation dynamicEffect, ResourceLocation lifecycleHandler, ResourceLocation recipeCheckHandler, FactoryUpgradeStartCost startCost, ResourceLocation startDataHandler, ResourceLocation startBusDataHandler, FactoryUpgradeRestrictions restrictions) {
    this(item, effect, compatibility, components, dynamicEffect, lifecycleHandler, recipeCheckHandler, startCost,
        startDataHandler, startBusDataHandler, restrictions, new FactoryUpgradeStartCost(0, 0), null, null);
  }

  public FactoryUpgradeDefinition(ResourceLocation item, FactoryUpgradeEntry effect, FactoryUpgradeCompatibility compatibility, JsonObject components, ResourceLocation dynamicEffect, ResourceLocation lifecycleHandler, ResourceLocation recipeCheckHandler, FactoryUpgradeStartCost startCost, ResourceLocation startDataHandler, ResourceLocation startBusDataHandler) {
    this(item, effect, compatibility, components, dynamicEffect, lifecycleHandler, recipeCheckHandler, startCost,
        startDataHandler, startBusDataHandler, FactoryUpgradeRestrictions.NONE);
  }

  public FactoryUpgradeDefinition(ResourceLocation item, FactoryUpgradeEntry effect) {
    this(item, effect, FactoryUpgradeCompatibility.unrestricted(), null, null, null, null);
  }

  public FactoryUpgradeDefinition(ResourceLocation item, FactoryUpgradeEntry effect, FactoryUpgradeCompatibility compatibility) {
    this(item, effect, compatibility, null, null, null, null);
  }

  public FactoryUpgradeDefinition(ResourceLocation item, FactoryUpgradeEntry effect, FactoryUpgradeCompatibility compatibility, JsonObject components) {
    this(item, effect, compatibility, components, null, null, null);
  }

  public FactoryUpgradeDefinition(ResourceLocation item, FactoryUpgradeEntry effect, FactoryUpgradeCompatibility compatibility, JsonObject components, ResourceLocation dynamicEffect) {
    this(item, effect, compatibility, components, dynamicEffect, null, null);
  }

  public FactoryUpgradeDefinition(ResourceLocation item, FactoryUpgradeEntry effect, FactoryUpgradeCompatibility compatibility, JsonObject components, ResourceLocation dynamicEffect, ResourceLocation lifecycleHandler) {
    this(item, effect, compatibility, components, dynamicEffect, lifecycleHandler, null, null);
  }

  public FactoryUpgradeDefinition(ResourceLocation item, FactoryUpgradeEntry effect, FactoryUpgradeCompatibility compatibility, JsonObject components, ResourceLocation dynamicEffect, ResourceLocation lifecycleHandler, ResourceLocation recipeCheckHandler) {
    this(item, effect, compatibility, components, dynamicEffect, lifecycleHandler, recipeCheckHandler, null);
  }

  public FactoryUpgradeDefinition(ResourceLocation item, FactoryUpgradeEntry effect, FactoryUpgradeCompatibility compatibility, JsonObject components, ResourceLocation dynamicEffect, ResourceLocation lifecycleHandler, ResourceLocation recipeCheckHandler, FactoryUpgradeStartCost startCost) {
    this(item, effect, compatibility, components, dynamicEffect, lifecycleHandler, recipeCheckHandler, startCost, null);
  }

  public FactoryUpgradeDefinition(ResourceLocation item, FactoryUpgradeEntry effect, FactoryUpgradeCompatibility compatibility, JsonObject components, ResourceLocation dynamicEffect, ResourceLocation lifecycleHandler, ResourceLocation recipeCheckHandler, FactoryUpgradeStartCost startCost, ResourceLocation startDataHandler) {
    this(item, effect, compatibility, components, dynamicEffect, lifecycleHandler, recipeCheckHandler, startCost,
        startDataHandler, null);
  }

  public FactoryUpgradeDefinition {
    Objects.requireNonNull(item, "item");
    Objects.requireNonNull(effect, "effect");
    Objects.requireNonNull(compatibility, "compatibility");
    Objects.requireNonNull(restrictions, "restrictions");
    if (restrictions.incompatible().contains(effect.id())) {
      throw new IllegalArgumentException("Upgrade cannot conflict with itself; use max_count");
    }
    if (startCost == null) {
      startCost = new FactoryUpgradeStartCost(0, 0);
    }
    if (tickCost == null) {
      tickCost = new FactoryUpgradeStartCost(0, 0);
    }
    if (tickDataHandler != null && tickDataHandler.toString().length() > 256) {
      throw new IllegalArgumentException("Runtime upgrade data handler identifier is too long");
    }
    if (finishDataHandler != null && finishDataHandler.toString().length() > 256) {
      throw new IllegalArgumentException("Completion upgrade data handler identifier is too long");
    }
    if (components != null) {
      if (components.size() > MAX_COMPONENTS) {
        throw new IllegalArgumentException("Too many upgrade item components");
      }
      components = components.deepCopy();
    }
    if (effect.id().toString().length() > 256) {
      throw new IllegalArgumentException("Upgrade identifier exceeds menu payload limit");
    }
    if (dynamicEffect != null && dynamicEffect.toString().length() > 256) {
      throw new IllegalArgumentException("Dynamic upgrade handler identifier is too long");
    }
    if (lifecycleHandler != null && lifecycleHandler.toString().length() > 256) {
      throw new IllegalArgumentException("Lifecycle upgrade handler identifier is too long");
    }
    effect.snapshot(0);
    if (recipeCheckHandler != null && recipeCheckHandler.toString().length() > 256) {
      throw new IllegalArgumentException("Recipe check upgrade handler identifier is too long");
    }
    if (startDataHandler != null && startDataHandler.toString().length() > 256) {
      throw new IllegalArgumentException("Upgrade data handler identifier is too long");
    }
    if (startBusDataHandler != null && startBusDataHandler.toString().length() > 256) {
      throw new IllegalArgumentException("Upgrade bus data handler identifier is too long");
    }
  }

  @Override
  public JsonObject components() {
    return components == null ? null : components.deepCopy();
  }

  public boolean exactComponents() {
    return components != null;
  }

  public ItemResourceKey compileMatcher(HolderLookup.Provider registries) {
    if (!exactComponents()) {
      throw new IllegalStateException("An item-wide upgrade has no exact component matcher");
    }
    var stack = new JsonObject();
    stack.addProperty("id", item.toString());
    stack.add("components", components.deepCopy());
    var prototype = ItemStack.SINGLE_ITEM_CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE), stack)
        .getOrThrow(message -> new JsonParseException(effect.id() + ": Invalid item components: " + message));

    return ItemResourceKey.of(prototype, registries);
  }

  public static FactoryUpgradeDefinition parse(ResourceLocation id, JsonElement source) {
    try {
      if (source == null || !source.isJsonObject()) {
        throw new IllegalArgumentException("Expected upgrade object");
      }
      var root = source.getAsJsonObject();
      if (!FIELDS.containsAll(root.keySet()) || integer(root, "schema_version", -1) != 1) {
        throw new IllegalArgumentException("Unknown upgrade field or unsupported schema_version");
      }
      var item = root.get("item");
      if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
        throw new IllegalArgumentException("item must be a resource identifier");
      }
      if (root.has("components") && !root.get("components").isJsonObject()) {
        throw new IllegalArgumentException("components must be an object");
      }
      ResourceLocation dynamic = null;
      if (root.has("dynamic_effect")) {
        var value = root.get("dynamic_effect");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
          throw new IllegalArgumentException("dynamic_effect must be a handler identifier");
        }
        dynamic = ResourceLocation.parse(value.getAsString());
      }
      ResourceLocation lifecycle = null;
      if (root.has("lifecycle_handler")) {
        var value = root.get("lifecycle_handler");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
          throw new IllegalArgumentException("lifecycle_handler must be a handler identifier");
        }
        lifecycle = ResourceLocation.parse(value.getAsString());
      }

      ResourceLocation check = null;
      if (root.has("recipe_check_handler")) {
        var value = root.get("recipe_check_handler");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
          throw new IllegalArgumentException("recipe_check_handler must be a handler identifier");
        }
        check = ResourceLocation.parse(value.getAsString());
      }
      var startCost = FactoryUpgradeStartCost.parse(root.get("start_cost"), "start_cost");

      return new FactoryUpgradeDefinition(
          ResourceLocation.parse(item.getAsString()),
          new FactoryUpgradeEntry(
              id,
              integer(root, "parallel_add", 0),
              integer(root, "speed_numerator", 1),
              integer(root, "speed_denominator", 1),
              integer(root, "energy_numerator", 1),
              integer(root, "energy_denominator", 1),
              integer(root, "fluid_numerator", 1),
              integer(root, "fluid_denominator", 1),
              root.has("recipe_modifiers") ? RecipeModifiers
                  .parse(root.get("recipe_modifiers"), id + ": $.recipe_modifiers") : List.of()),
          new FactoryUpgradeCompatibility(
              machines(root, "compatible_machines"),
              machines(root, "incompatible_machines")),
          root.has("components") ? root.getAsJsonObject("components") : null,
          dynamic,
          lifecycle,
          check,
          startCost,
          handlerIdentifier(root, "start_data_handler"),
          handlerIdentifier(root, "start_bus_data_handler"),
          FactoryUpgradeRestrictions.parse(root, id),
          FactoryUpgradeStartCost.parse(root.get("tick_cost"), "tick_cost"),
          handlerIdentifier(root, "tick_data_handler"),
          handlerIdentifier(root, "finish_data_handler"));
    } catch (RuntimeException invalid) {
      throw new JsonParseException(id + ": Invalid fixed upgrade: " + invalid.getMessage(), invalid);
    }
  }

  private static ResourceLocation handlerIdentifier(JsonObject root, String field) {
    if (!root.has(field)) {
      return null;
    }
    var value = root.get(field);
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
      throw new IllegalArgumentException(field + " must be a handler identifier");
    }

    return ResourceLocation.parse(value.getAsString());
  }

  private static Set<ResourceLocation> machines(JsonObject root, String field) {
    if (!root.has(field)) {
      return Set.of();
    }
    var value = root.get(field);
    if (!value.isJsonArray() || value.getAsJsonArray().size() > FactoryUpgradeCompatibility.MAX_MACHINE_IDS) {
      throw new IllegalArgumentException(field + " must be a bounded list of machine identifiers");
    }
    var machines = new LinkedHashSet<ResourceLocation>();
    for (var entry : value.getAsJsonArray()) {
      if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) {
        throw new IllegalArgumentException(field + " contains a non-string machine identifier");
      }
      var id = ResourceLocation.parse(entry.getAsString());
      if (id.toString().length() > 256 || !machines.add(id)) {
        throw new IllegalArgumentException(field + " contains an oversized or duplicate machine identifier");
      }
    }

    return Set.copyOf(machines);
  }

  private static int integer(JsonObject root, String field, int fallback) {
    if (!root.has(field)) {
      return fallback;
    }
    var value = root.get(field);
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
      throw new IllegalArgumentException(field + " must be an integer");
    }

    return value.getAsBigDecimal().intValueExact();
  }
}
