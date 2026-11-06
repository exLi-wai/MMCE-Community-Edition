package com.lw.modularmachine.machine.recipe;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.lw.modularmachine.machine.smart.SmartNumberRequirement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pure structural validation. Registry/component resolution belongs to the
 * native codec.
 */
public final class MachineRecipeJson {
  public static final String TYPE = "mmcece:machine";
  /**
   * KubeJS adds this marker to every generated recipe for its own change
   * tracking.
   */
  public static final String KUBEJS_CHANGED_MARKER = "_kubejs_changed_marker";
  public static final int MAX_DEMANDS = 256;
  public static final Set<String> FIELDS = Set.of(
      "type",
      "machine",
      "priority",
      "duration",
      "energy_per_tick",
      "fluid_per_tick",
      "fluid_inputs",
      "fluid_outputs",
      "failure_action",
      "random_seed",
      "modifiers",
      "interface_inputs",
      "selectors",
      "inputs",
      "outputs",
      "neoforge:conditions");

  private MachineRecipeJson() {
  }

  public record Header(
      String machine,
      int priority,
      int duration,
      long energyPerTick,
      boolean hasFluidPerTick,
      boolean hasFluidInputs,
      boolean hasFluidOutputs,
      String failureAction,
      long randomSeed,
      RecipeSelectors selectors) {
  }

  public static Header validate(JsonElement root) {
    var object = object(root, "$");
    fields(object, FIELDS, "$");
    if (object.has("type") && !TYPE.equals(string(object.get("type"), "$.type"))) {
      throw error("$.type", "Expected " + TYPE);
    }
    String machine = identifier(object.get("machine"), "$.machine");
    long priority = object.has("priority") ? integer(object.get("priority"), "$.priority") : 0;
    if (priority < Integer.MIN_VALUE || priority > Integer.MAX_VALUE) {
      throw error("$.priority", "Outside int range");
    }
    long duration = integer(object.get("duration"), "$.duration");
    if (duration < 1 || duration > Integer.MAX_VALUE) {
      throw error("$.duration", "Expected positive int ticks");
    }
    long energyPerTick = object
        .has("energy_per_tick") ? integer(object.get("energy_per_tick"), "$.energy_per_tick") : 0;
    if (energyPerTick < 0) {
      throw error("$.energy_per_tick", "Expected a non-negative FE/t value in schema 2");
    }
    if (object.has("fluid_per_tick")) {
      var fluid = object(object.get("fluid_per_tick"), "$.fluid_per_tick");
      fields(fluid, Set.of("id", "amount", "components"), "$.fluid_per_tick");
      identifier(fluid.get("id"), "$.fluid_per_tick.id");
      long fluidAmount = integer(fluid.get("amount"), "$.fluid_per_tick.amount");
      if (fluidAmount <= 0 || fluidAmount > Integer.MAX_VALUE) {
        throw error("$.fluid_per_tick.amount", "Expected positive int millibuckets");
      }
      if (fluid.has("components") && !fluid.get("components").isJsonObject()) {
        throw error("$.fluid_per_tick.components", "Expected component map");
      }
    }
    if (object.has("fluid_inputs")) {
      if (!object.get("fluid_inputs").isJsonArray()) {
        throw error("$.fluid_inputs", "Fluid inputs require schema 2 and an array");
      }
      var inputs = object.getAsJsonArray("fluid_inputs");
      if (inputs.isEmpty() || inputs.size() > MAX_DEMANDS) {
        throw error("$.fluid_inputs", "Expected 1.." + MAX_DEMANDS + " requirements");
      }
      for (int i = 0; i < inputs.size(); i++) {
        String path = "$.fluid_inputs[" + i + "]";
        var fluid = object(inputs.get(i), path);
        fields(fluid, Set.of("id", "amount", "components"), path);
        identifier(fluid.get("id"), path + ".id");
        long amount = integer(fluid.get("amount"), path + ".amount");
        if (amount <= 0 || amount > Integer.MAX_VALUE) {
          throw error(path + ".amount", "Expected positive int millibuckets");
        }
        if (fluid.has("components") && !fluid.get("components").isJsonObject()) {
          throw error(path + ".components", "Expected component map");
        }
      }
    }
    validateFluidList(object, "fluid_outputs", "Fluid outputs require an array");
    String failureAction = object
        .has("failure_action") ? string(object.get("failure_action"), "$.failure_action") : "still";
    try {
      RecipeFailureAction.parse(failureAction);
    } catch (IllegalArgumentException invalid) {
      throw error("$.failure_action", invalid.getMessage());
    }
    long randomSeed = object.has("random_seed") ? integer(object.get("random_seed"), "$.random_seed") : 0;
    if (randomSeed < 0) {
      throw error("$.random_seed", "Random seeds require a non-negative schema 2 long");
    }
    if (object.has("modifiers")) {
      RecipeModifiers.parse(object.get("modifiers"), "$.modifiers");
    }
    demands(object.get("inputs"), "$.inputs");
    demands(object.get("outputs"), "$.outputs");
    if (object.has("interface_inputs")) {
      SmartNumberRequirement.parse(object.get("interface_inputs"), "$.interface_inputs");
    }
    var selectors = object.has("selectors") ? RecipeSelectors
        .parse(object.get("selectors"), "$.selectors") : RecipeSelectors.NONE;
    if (object.has("neoforge:conditions") && !object.get("neoforge:conditions").isJsonArray()) {
      throw error("$.neoforge:conditions", "Expected array");
    }
    return new Header(
        machine,
        (int) priority,
        (int) duration,
        energyPerTick,
        object.has("fluid_per_tick"),
        object.has("fluid_inputs"),
        object.has("fluid_outputs"),
        failureAction,
        randomSeed,
        selectors);
  }

  /**
   * Resolve once before native decoding; effective recipes never retain
   * executable modifiers.
   */
  public static JsonObject normalize(JsonElement root) {
    return normalize(root, List.of());
  }

  /**
   * Merge declaration sources before evaluation; never feed a previously
   * normalized value back in.
   */
  public static JsonObject normalize(JsonElement root, List<RecipeModifier> inheritedRules) {
    validate(root);
    var source = root.getAsJsonObject();
    if (!source.has("modifiers") && inheritedRules.isEmpty()) {
      return source;
    }
    var rules = new ArrayList<RecipeModifier>();
    if (source.has("modifiers")) {
      rules.addAll(RecipeModifiers.parse(source.get("modifiers"), "$.modifiers"));
    }
    rules.addAll(inheritedRules);
    final RecipeModifiers modifiers;
    try {
      modifiers = new RecipeModifiers(rules);
    } catch (RuntimeException invalid) {
      throw new JsonParseException("$.modifiers: " + invalid.getMessage(), invalid);
    }
    var result = source.deepCopy();
    result.remove("modifiers");
    try {
      result.addProperty("duration", modifiers.duration((int) integer(source.get("duration"), "$.duration")));
      long baseEnergy = source.has("energy_per_tick") ? integer(source.get("energy_per_tick"), "$.energy_per_tick") : 0;
      long energy = modifiers
          .amount(RecipeModifier.Target.ENERGY, RecipeModifier.Io.INPUT, baseEnergy, 0, Long.MAX_VALUE);
      if (energy > 0 || source.has("energy_per_tick")) {
        result.addProperty("energy_per_tick", energy);
      }
      modifyItems(result, "inputs", RecipeModifier.Io.INPUT, modifiers);
      modifyItems(result, "outputs", RecipeModifier.Io.OUTPUT, modifiers);
      modifyFluids(result, "fluid_inputs", RecipeModifier.Io.INPUT, modifiers);
      modifyFluids(result, "fluid_outputs", RecipeModifier.Io.OUTPUT, modifiers);
      if (result.has("fluid_per_tick")) {
        var fluid = result.getAsJsonObject("fluid_per_tick");
        long amount = modifiers.amount(
            RecipeModifier.Target.FLUID,
            RecipeModifier.Io.INPUT,
            integer(fluid.get("amount"), "$.fluid_per_tick.amount"),
            0,
            Integer.MAX_VALUE);
        if (amount == 0) {
          result.remove("fluid_per_tick");
        } else {
          fluid.addProperty("amount", amount);
        }
      }
    } catch (RuntimeException invalid) {
      throw new JsonParseException("$.modifiers: " + invalid.getMessage(), invalid);
    }
    validate(result);

    return result;
  }

  private static void modifyItems(JsonObject root, String field, RecipeModifier.Io io, RecipeModifiers modifiers) {
    var entries = root.getAsJsonArray(field);
    for (int index = 0; index < entries.size(); index++) {
      var entry = entries.get(index).getAsJsonObject();
      String path = "$." + field + "[" + index + "]";
      entry.addProperty(
          "amount",
          modifiers.amount(
              RecipeModifier.Target.ITEM,
              io,
              integer(entry.get("amount"), path + ".amount"),
              1,
              Long.MAX_VALUE));
      if (io == RecipeModifier.Io.OUTPUT) {
        double chance = entry.has("chance") ? decimal(entry.get("chance"), path + ".chance") : 1.0D;
        entry.addProperty("chance", modifiers.chance(RecipeModifier.Target.ITEM, io, chance));
      }
    }
  }

  private static void modifyFluids(JsonObject root, String field, RecipeModifier.Io io, RecipeModifiers modifiers) {
    if (!root.has(field)) {
      return;
    }
    var entries = root.getAsJsonArray(field);
    for (int index = 0; index < entries.size(); index++) {
      var entry = entries.get(index).getAsJsonObject();
      entry.addProperty(
          "amount",
          modifiers.amount(
              RecipeModifier.Target.FLUID,
              io,
              integer(entry.get("amount"), "$." + field + "[" + index + "].amount"),
              1,
              Integer.MAX_VALUE));
    }
  }

  private static void validateFluidList(JsonObject root, String field, String schemaError) {
    if (!root.has(field)) {
      return;
    }
    if (!root.get(field).isJsonArray()) {
      throw error("$." + field, schemaError);
    }
    var values = root.getAsJsonArray(field);
    if (values.isEmpty() || values.size() > MAX_DEMANDS) {
      throw error("$." + field, "Expected 1.." + MAX_DEMANDS + " requirements");
    }
    for (int i = 0; i < values.size(); i++) {
      String path = "$." + field + "[" + i + "]";
      var fluid = object(values.get(i), path);
      fields(fluid, Set.of("id", "amount", "components"), path);
      identifier(fluid.get("id"), path + ".id");
      long amount = integer(fluid.get("amount"), path + ".amount");
      if (amount <= 0 || amount > Integer.MAX_VALUE) {
        throw error(path + ".amount", "Expected positive int millibuckets");
      }
      if (fluid.has("components") && !fluid.get("components").isJsonObject()) {
        throw error(path + ".components", "Expected component map");
      }
    }
  }

  private static void demands(JsonElement element, String path) {
    if (element == null || !element.isJsonArray()) {
      throw error(path, "Expected array");
    }
    var array = element.getAsJsonArray();
    if (array.isEmpty() || array.size() > MAX_DEMANDS) {
      throw error(path, "Expected 1.." + MAX_DEMANDS + " requirements");
    }
    for (int i = 0; i < array.size(); i++) {
      String at = path + "[" + i + "]";
      var demand = object(array.get(i), at);
      fields(demand, Set.of("stack", "amount", "chance", "selector"), at);
      if (demand.has("selector")) {
        ResourceSelector.require(string(demand.get("selector"), at + ".selector"));
      }
      long amount = integer(demand.get("amount"), at + ".amount");
      if (amount <= 0) {
        throw error(at + ".amount", "Expected positive long");
      }
      if (demand.has("chance")) {
        double chance = decimal(demand.get("chance"), at + ".chance");
        if (chance < 0 || chance > 1) {
          throw error(at + ".chance", "Expected a number from 0 to 1");
        }
      }
      var stack = object(demand.get("stack"), at + ".stack");
      fields(stack, Set.of("id", "components"), at + ".stack");
      identifier(stack.get("id"), at + ".stack.id");
      if (stack.has("components") && !stack.get("components").isJsonObject()) {
        throw error(at + ".stack.components", "Expected component map");
      }
    }
  }

  public static long integer(JsonElement value, String path) {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
      throw error(path, "Expected JSON integer");
    }
    try {
      return value.getAsBigDecimal().longValueExact();
    } catch (ArithmeticException | NumberFormatException invalid) {
      throw error(path, "Fractional or out-of-range integer");
    }
  }

  private static double decimal(JsonElement value, String path) {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
      throw error(path, "Expected JSON number");
    }
    try {
      double result = value.getAsDouble();
      if (!Double.isFinite(result)) {
        throw new NumberFormatException();
      }
      return result;
    } catch (NumberFormatException invalid) {
      throw error(path, "Expected finite JSON number");
    }
  }

  private static String identifier(JsonElement value, String path) {
    String text = string(value, path);
    if (!text.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
      throw error(path, "Expected explicit namespace:path");
    }
    return text;
  }

  private static String string(JsonElement value, String path) {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
      throw error(path, "Expected string");
    }
    return value.getAsString();
  }

  private static JsonObject object(JsonElement value, String path) {
    if (value == null || !value.isJsonObject()) {
      throw error(path, "Expected object");
    }
    return value.getAsJsonObject();
  }

  private static void fields(JsonObject object, Set<String> allowed, String path) {
    for (String field : object.keySet()) {
      if (!allowed.contains(field)) {
        throw error(path + "." + field, "Unsupported field");
      }
    }
  }

  private static JsonParseException error(String path, String message) {
    return new JsonParseException(path + ": " + message);
  }
}
