package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.machine.recipe.RecipeModifiers;
import com.mojang.serialization.JsonOps;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeRuntimeData;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeBusData;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeItemData;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeRecipeChecks;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeEvents;
import com.lw.modularmachine.api.upgrade.FactoryDynamicUpgrades;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/** Immutable server registrations supplied by KubeJS server scripts. */
public final class FactoryUpgradeKubeJSHandlers {
  /** Fluent KubeJS builder; JSON is kept private as the validation DTO. */
  public static final class FixedUpgradeBuilder {
    private final String idText;
    private final JsonObject source = new JsonObject();
    private boolean valid = true;

    private FixedUpgradeBuilder(String id, String item) {
      idText = id;
      source.addProperty("schema_version", 1);
      if (item == null || item.isBlank()) {
        valid = false;
      } else {
        source.addProperty("item", item);
      }
    }

    public FixedUpgradeBuilder parallelAdd(int value) {
      number("parallel_add", value);
      return this;
    }

    public FixedUpgradeBuilder parallel(int value) {
      return parallelAdd(value);
    }

    public FixedUpgradeBuilder speed(int numerator, int denominator) {
      number("speed_numerator", numerator);
      number("speed_denominator", denominator);
      return this;
    }

    public FixedUpgradeBuilder energy(int numerator, int denominator) {
      number("energy_numerator", numerator);
      number("energy_denominator", denominator);
      return this;
    }

    public FixedUpgradeBuilder fluid(int numerator, int denominator) {
      number("fluid_numerator", numerator);
      number("fluid_denominator", denominator);
      return this;
    }

    public FixedUpgradeBuilder compatibleMachine(String id) {
      addString("compatible_machines", id);
      return this;
    }

    public FixedUpgradeBuilder incompatibleMachine(String id) {
      addString("incompatible_machines", id);
      return this;
    }

    public FixedUpgradeBuilder incompatibleUpgrade(String id) {
      addString("incompatible_upgrades", id);
      return this;
    }

    public FixedUpgradeBuilder exclusiveGroup(String id) {
      source.addProperty("exclusive_group", id);
      return this;
    }

    public FixedUpgradeBuilder maxCount(int value) {
      number("max_count", value);
      return this;
    }

    public FixedUpgradeBuilder dynamicEffect(String id) {
      source.addProperty("dynamic_effect", id);
      return this;
    }

    public FixedUpgradeBuilder lifecycleHandler(String id) {
      source.addProperty("lifecycle_handler", id);
      return this;
    }

    public FixedUpgradeBuilder recipeCheckHandler(String id) {
      source.addProperty("recipe_check_handler", id);
      return this;
    }

    public FixedUpgradeBuilder startDataHandler(String id) {
      source.addProperty("start_data_handler", id);
      return this;
    }

    public FixedUpgradeBuilder startBusDataHandler(String id) {
      source.addProperty("start_bus_data_handler", id);
      return this;
    }

    public FixedUpgradeBuilder tickDataHandler(String id) {
      source.addProperty("tick_data_handler", id);
      return this;
    }

    public FixedUpgradeBuilder finishDataHandler(String id) {
      source.addProperty("finish_data_handler", id);
      return this;
    }

    public FixedUpgradeBuilder startCost(int consume, int durability) {
      costs("start_cost", consume, durability);
      return this;
    }

    public FixedUpgradeBuilder tickCost(int consume, int durability) {
      costs("tick_cost", consume, durability);
      return this;
    }

    public FixedUpgradeBuilder component(String id, String value) {
      components().addProperty(id, value);
      return this;
    }

    public FixedUpgradeBuilder componentNumber(String id, double value) {
      components().addProperty(id, value);
      return this;
    }

    public FixedUpgradeBuilder componentBoolean(String id, boolean value) {
      components().addProperty(id, value);
      return this;
    }

    /** Advanced escape hatch for a component value with a structured JSON shape. */
    public FixedUpgradeBuilder componentJson(String id, String jsonValue) {
      try {
        components().add(id, JsonParser.parseString(jsonValue));
      } catch (RuntimeException invalid) {
        valid = false;
      }
      return this;
    }

    public FixedUpgradeBuilder recipeModifier(String target, String io, String operation, double value) {
      return recipeModifier(target, io, operation, value, false);
    }

    public FixedUpgradeBuilder recipeModifier(String target, String io, String operation, double value, boolean affectChance) {
      try {
        return addModifier(
            new RecipeModifier(
                RecipeModifier.Target.parse(target),
                RecipeModifier.Io.parse(io),
                RecipeModifier.Operation.parse(operation),
                value,
                affectChance));
      } catch (RuntimeException invalid) {
        valid = false;
        throw invalid;
      }
    }

    public FixedUpgradeBuilder addModifier(RecipeModifier modifier) {
      var modifiers = source.has("recipe_modifiers") ? source.getAsJsonArray("recipe_modifiers") : new JsonArray();
      if (modifier == null || modifiers.size() >= RecipeModifiers.MAX_RULES) {
        valid = false;
        throw new IllegalArgumentException("Missing or excessive fixed upgrade recipe modifiers");
      }
      modifiers.add(
          RecipeModifier.CODEC.encodeStart(JsonOps.INSTANCE, Objects.requireNonNull(modifier, "modifier"))
              .getOrThrow());
      source.add("recipe_modifiers", modifiers);
      return this;
    }

    public FixedUpgradeBuilder addModifiers(List<RecipeModifier> modifiers) {
      Objects.requireNonNull(modifiers, "modifiers");
      for (var modifier : modifiers) {
        addModifier(modifier);
      }
      return this;
    }

    private void number(String field, int value) {
      source.addProperty(field, value);
    }

    private JsonObject components() {
      if (!source.has("components")) {
        source.add("components", new JsonObject());
      }
      return source.getAsJsonObject("components");
    }

    private void addString(String field, String value) {
      var values = source.has(field) ? source.getAsJsonArray(field) : new JsonArray();
      values.add(value);
      source.add(field, values);
    }

    private void costs(String field, int consume, int durability) {
      var cost = new JsonObject();
      cost.addProperty("consume", consume);
      cost.addProperty("durability", durability);
      source.add(field, cost);
    }

    private void invalidate() {
      valid = false;
    }

    private FactoryUpgradeDefinition compile() {
      if (!valid) {
        throw new JsonParseException("Invalid KubeJS fixed upgrade builder");
      }
      return FactoryUpgradeDefinition.parse(ResourceLocation.parse(idText), source);
    }
  }

  public static final class Registration {
    private final Map<ResourceLocation, FactoryDynamicUpgrades.Evaluator> dynamic = new HashMap<>();
    private final Map<ResourceLocation, FactoryUpgradeRecipeChecks.Checker> checks = new HashMap<>();
    private final Map<ResourceLocation, FactoryUpgradeEvents.Listener> events = new HashMap<>();
    private final Map<ResourceLocation, FactoryUpgradeItemData.Preparer> itemData = new HashMap<>();
    private final Map<ResourceLocation, FactoryUpgradeBusData.Preparer> busData = new HashMap<>();
    private final Map<ResourceLocation, FactoryUpgradeRuntimeData.Preparer> runtimeData = new HashMap<>();
    private final List<FixedUpgradeBuilder> definitions = new ArrayList<>();
    private final Set<ResourceLocation> definitionIdentifiers = new LinkedHashSet<>();
    private final Set<ResourceLocation> identifiers = new LinkedHashSet<>();
    private boolean valid = true;

    /** Starts one fixed item upgrade definition in the KubeJS server script. */
    public FixedUpgradeBuilder fixedUpgrade(String item, String id) {
      ResourceLocation identifier = ResourceLocation.tryParse(id);
      if (identifier == null
          || !identifier.getNamespace().equals("kubejs")
          || !identifier.toString().equals(id)
          || !definitionIdentifiers.add(identifier)
          || definitions.size() >= FactoryUpgradeService.MAX_DEFINITIONS) {
        valid = false;
        var invalid = new FixedUpgradeBuilder(id, item);
        invalid.invalidate();
        return invalid;
      }
      var builder = new FixedUpgradeBuilder(id, item);
      definitions.add(builder);
      return builder;
    }

    public void dynamic(String id, FactoryDynamicUpgrades.Evaluator handler) {
      put(dynamic, id, handler);
    }

    public void recipeCheck(String id, FactoryUpgradeRecipeChecks.Checker handler) {
      put(checks, id, handler);
    }

    public void lifecycle(String id, FactoryUpgradeEvents.Listener handler) {
      put(events, id, handler);
    }

    public void startItemData(String id, FactoryUpgradeItemData.Preparer handler) {
      put(itemData, id, handler);
    }

    public void startBusData(String id, FactoryUpgradeBusData.Preparer handler) {
      put(busData, id, handler);
    }

    public void runtimeData(String id, FactoryUpgradeRuntimeData.Preparer handler) {
      put(runtimeData, id, handler);
    }

    private <T> void put(Map<ResourceLocation, T> handlers, String text, T handler) {
      ResourceLocation id = ResourceLocation.tryParse(text);
      if (id == null
          || !id.getNamespace().equals("kubejs")
          || id.getPath().isEmpty()
          || id.toString().length() > 256
          || !id.toString().equals(text)
          || handler == null
          || handlers.containsKey(id)
          || !identifiers.contains(id) && identifiers.size() >= FactoryUpgradeHandlers.MAX_HANDLERS) {
        valid = false;
        return;
      }
      identifiers.add(id);
      handlers.put(id, handler);
    }

    public boolean valid() {
      return valid;
    }

    public Snapshot snapshot() {
      List<FactoryUpgradeDefinition> compiled = new ArrayList<>();
      if (valid) {
        try {
          definitions.stream().map(FixedUpgradeBuilder::compile).forEach(compiled::add);
        } catch (RuntimeException invalid) {
          valid = false;
        }
      }
      return !valid ? Snapshot.EMPTY : new Snapshot(dynamic, checks, events, itemData, busData, runtimeData, compiled);
    }
  }

  public record Snapshot(
      Map<ResourceLocation, FactoryDynamicUpgrades.Evaluator> dynamic,
      Map<ResourceLocation, FactoryUpgradeRecipeChecks.Checker> checks,
      Map<ResourceLocation, FactoryUpgradeEvents.Listener> events,
      Map<ResourceLocation, FactoryUpgradeItemData.Preparer> itemData,
      Map<ResourceLocation, FactoryUpgradeBusData.Preparer> busData,
      Map<ResourceLocation, FactoryUpgradeRuntimeData.Preparer> runtimeData,
      List<FactoryUpgradeDefinition> definitions) {
    public static final Snapshot EMPTY = new Snapshot(
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        List.of());

    public Snapshot {
      dynamic = Map.copyOf(dynamic);
      checks = Map.copyOf(checks);
      events = Map.copyOf(events);
      itemData = Map.copyOf(itemData);
      busData = Map.copyOf(busData);
      runtimeData = Map.copyOf(runtimeData);
      definitions = List.copyOf(definitions);
    }
  }

  private static volatile Snapshot current = Snapshot.EMPTY;

  private FactoryUpgradeKubeJSHandlers() {
  }

  public static Registration registration() {
    return new Registration();
  }

  public static void install(Snapshot candidate) {
    FactoryUpgradeHandlers.requireServer();
    var next = Objects.requireNonNull(candidate, "candidate");
    FactoryUpgradeService.replaceDefinitions(next.definitions());
    if (!next.equals(current)) {
      current = next;
      FactoryUpgradeService.invalidateDynamicEffects();
    }
  }

  public static FactoryDynamicUpgrades.Evaluator dynamic(ResourceLocation id) {
    return current.dynamic().get(id);
  }

  public static FactoryUpgradeRecipeChecks.Checker recipeCheck(ResourceLocation id) {
    return current.checks().get(id);
  }

  public static FactoryUpgradeEvents.Listener lifecycle(ResourceLocation id) {
    return current.events().get(id);
  }

  public static boolean hasEvents() {
    return !current.events().isEmpty();
  }

  public static FactoryUpgradeItemData.Preparer startItemData(ResourceLocation id) {
    return current.itemData().get(id);
  }

  public static FactoryUpgradeBusData.Preparer startBusData(ResourceLocation id) {
    return current.busData().get(id);
  }

  public static FactoryUpgradeRuntimeData.Preparer runtimeData(ResourceLocation id) {
    return current.runtimeData().get(id);
  }

  static void clearOnServerStop() {
    current = Snapshot.EMPTY;
  }
}
