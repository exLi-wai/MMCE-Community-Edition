package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.recipe.RecipeSelectors;
import com.lw.modularmachine.api.recipe.RecipeFailureAction;
import com.lw.modularmachine.api.recipe.ChemicalRecipeData;
import com.mojang.serialization.JsonOps;
import com.lw.modularmachine.config.MachineConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import com.lw.modularmachine.api.smart.SmartNumberRequirement;
import com.lw.modularmachine.api.recipe.RecipeExecutionOptions;
import com.lw.modularmachine.api.recipe.RecipeScriptOptions;
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
      "energy_output_per_tick",
      "thread_name",
      "max_threads",
      "recipe_tooltips",
      "item_callbacks",
      "fluid_per_tick",
      "fluid_inputs",
      "fluid_outputs",
      "failure_action",
      "parallelized",
      "random_seed",
      "modifiers",
      "interface_inputs",
      "selectors",
      "chemicals",
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
      long energyOutputPerTick,
      boolean hasFluidPerTick,
      boolean hasFluidInputs,
      boolean hasFluidOutputs,
      String failureAction,
      boolean parallelized,
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
    long energyOutputPerTick = object
        .has("energy_output_per_tick") ? integer(object.get("energy_output_per_tick"), "$.energy_output_per_tick") : 0;
    if (energyOutputPerTick < 0) {
      throw error("$.energy_output_per_tick", "Expected a non-negative FE/t output value");
    }
    RecipeExecutionOptions.CODEC.parse(JsonOps.INSTANCE, object).getOrThrow();
    if (object.has("max_threads")) {
      long maximumThreads = integer(object.get("max_threads"), "$.max_threads");
      if (maximumThreads < 0 || maximumThreads > RecipeExecutionOptions.MAX_THREADS) {
        throw error("$.max_threads", "Outside the factory thread range");
      }
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
        fields(fluid, Set.of("id", "amount", "components", "chance", "parallel_unaffected"), path);
        fixedFlag(fluid, path);
        identifier(fluid.get("id"), path + ".id");
        long amount = integer(fluid.get("amount"), path + ".amount");
        if (amount <= 0 || amount > Integer.MAX_VALUE) {
          throw error(path + ".amount", "Expected positive int millibuckets");
        }
        if (fluid.has("components") && !fluid.get("components").isJsonObject()) {
          throw error(path + ".components", "Expected component map");
        }
        probability(fluid, path);
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
    boolean parallelized = true;
    if (object.has("parallelized")) {
      var value = object.get("parallelized");
      if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
        throw error("$.parallelized", "Expected boolean");
      }
      parallelized = value.getAsBoolean();
    }
    long randomSeed = object.has("random_seed") ? integer(object.get("random_seed"), "$.random_seed") : 0;
    if (randomSeed < 0) {
      throw error("$.random_seed", "Random seeds require a non-negative schema 2 long");
    }
    if (object.has("modifiers")) {
      RecipeModifiers.parse(object.get("modifiers"), "$.modifiers");
    }
    demands(object.get("inputs"), "$.inputs", true);
    demands(object.get("outputs"), "$.outputs", false);
    validateScriptOptions(object);
    var chemicals = validateChemicals(object);
    if (object.getAsJsonArray("inputs").isEmpty()
        && !object.has("fluid_inputs")
        && !object.has("fluid_per_tick")
        && energyPerTick == 0
        && chemicals.inputs().isEmpty()
        && chemicals.perTick().isEmpty()
        || object.getAsJsonArray("outputs").isEmpty()
            && !object.has("fluid_outputs")
            && chemicals.outputs().isEmpty()
            && energyOutputPerTick == 0) {
      throw error("$", "A machine recipe requires resource input and output");
    }
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
        energyOutputPerTick,
        object.has("fluid_per_tick"),
        object.has("fluid_inputs"),
        object.has("fluid_outputs"),
        failureAction,
        parallelized,
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
    return normalize(root, inheritedRules, false);
  }

  /**
   * Internal contextual energy policy; frozen effective declarations never apply
   * it again.
   */
  static JsonObject normalize(JsonElement root, List<RecipeModifier> inheritedRules, boolean durationMultiplier) {
    return normalize(root, inheritedRules, durationMultiplier, currentDefaults());
  }

  /** Internal immutable defaults captured for one candidate compilation. */
  record Defaults(boolean parallelized, RecipeFailureAction failureAction) {
  }

  static Defaults currentDefaults() {
    return new Defaults(MachineConfig.recipeParallelDefault(), MachineConfig.defaultFailureAction());
  }

  static JsonObject normalize(JsonElement root, List<RecipeModifier> inheritedRules, boolean durationMultiplier, Defaults defaults) {
    validate(root);
    var source = root.getAsJsonObject();
    if (!source.has("parallelized") || !source.has("failure_action")) {
      source = source.deepCopy();
      if (!source.has("parallelized")) {
        source.addProperty("parallelized", defaults.parallelized());
      }
      if (!source.has("failure_action")) {
        source.addProperty("failure_action", defaults.failureAction().serialized());
      }
    }
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
      int originalDuration = (int) integer(source.get("duration"), "$.duration");
      int duration = modifiers.duration(originalDuration);
      result.addProperty("duration", duration);
      long baseEnergy = source.has("energy_per_tick") ? integer(source.get("energy_per_tick"), "$.energy_per_tick") : 0;
      long energy = modifiers.amount(
          RecipeModifier.Target.ENERGY,
          RecipeModifier.Io.INPUT,
          baseEnergy,
          0,
          Long.MAX_VALUE,
          durationMultiplier ? originalDuration : 1,
          durationMultiplier ? duration : 1);
      if (energy > 0 || source.has("energy_per_tick")) {
        result.addProperty("energy_per_tick", energy);
      }
      long baseOutputEnergy = source.has(
          "energy_output_per_tick") ? integer(source.get("energy_output_per_tick"), "$.energy_output_per_tick") : 0;
      long outputEnergy = modifiers
          .amount(RecipeModifier.Target.ENERGY, RecipeModifier.Io.OUTPUT, baseOutputEnergy, 0, Long.MAX_VALUE);
      if (outputEnergy > 0 || source.has("energy_output_per_tick")) {
        result.addProperty("energy_output_per_tick", outputEnergy);
      }
      modifyItems(result, "inputs", RecipeModifier.Io.INPUT, modifiers);
      modifyItems(result, "outputs", RecipeModifier.Io.OUTPUT, modifiers);
      modifyFluids(result, "fluid_inputs", RecipeModifier.Io.INPUT, modifiers);
      modifyFluids(result, "fluid_outputs", RecipeModifier.Io.OUTPUT, modifiers);
      if (result.has("chemicals")) {
        var chemicals = result.getAsJsonObject("chemicals");
        modifyChemicals(chemicals, "inputs", RecipeModifier.Io.INPUT, modifiers);
        modifyChemicals(chemicals, "outputs", RecipeModifier.Io.OUTPUT, modifiers);
        modifyChemicals(chemicals, "per_tick", RecipeModifier.Io.INPUT, modifiers);
      }
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
      if (io == RecipeModifier.Io.OUTPUT) {
        double chance = entry.has("chance") ? decimal(entry.get("chance"), "$." + field + "[" + index + "].chance") : 1;
        entry.addProperty("chance", modifiers.chance(RecipeModifier.Target.FLUID, io, chance));
      }
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
      fields(fluid, Set.of("id", "amount", "components", "chance", "parallel_unaffected"), path);
      fixedFlag(fluid, path);
      identifier(fluid.get("id"), path + ".id");
      long amount = integer(fluid.get("amount"), path + ".amount");
      if (amount <= 0 || amount > Integer.MAX_VALUE) {
        throw error(path + ".amount", "Expected positive int millibuckets");
      }
      if (fluid.has("components") && !fluid.get("components").isJsonObject()) {
        throw error(path + ".components", "Expected component map");
      }
      probability(fluid, path);
    }
  }

  private static ChemicalRecipeData validateChemicals(JsonObject root) {
    if (!root.has("chemicals")) {
      return ChemicalRecipeData.NONE;
    }
    var chemicals = object(root.get("chemicals"), "$.chemicals");
    fields(chemicals, Set.of("inputs", "outputs", "per_tick"), "$.chemicals");
    for (String field : List.of("inputs", "outputs", "per_tick")) {
      if (!chemicals.has(field)) {
        continue;
      }
      String path = "$.chemicals." + field;
      if (!chemicals.get(field).isJsonArray() || chemicals.getAsJsonArray(field).size() > MAX_DEMANDS) {
        throw error(path, "Expected an array with at most " + MAX_DEMANDS + " requirements");
      }
      var values = chemicals.getAsJsonArray(field);
      for (int index = 0; index < values.size(); index++) {
        String at = path + "[" + index + "]";
        var value = object(values.get(index), at);
        fields(value, Set.of("chemical", "amount", "selector"), at);
        identifier(value.get("chemical"), at + ".chemical");
        if (integer(value.get("amount"), at + ".amount") <= 0) {
          throw error(at + ".amount", "Expected a positive long chemical amount");
        }
        if (value.has("selector")) {
          ResourceSelector.require(string(value.get("selector"), at + ".selector"));
        }
      }
    }
    try {
      return ChemicalRecipeData.CODEC.parse(JsonOps.INSTANCE, chemicals).getOrThrow();
    } catch (RuntimeException invalid) {
      throw new JsonParseException("$.chemicals: " + invalid.getMessage(), invalid);
    }
  }

  private static void modifyChemicals(JsonObject root, String field, RecipeModifier.Io io, RecipeModifiers modifiers) {
    if (!root.has(field)) {
      return;
    }
    var entries = root.getAsJsonArray(field);
    for (int index = 0; index < entries.size(); index++) {
      var entry = entries.get(index).getAsJsonObject();
      entry.addProperty(
          "amount",
          modifiers.amount(
              RecipeModifier.Target.CHEMICAL,
              io,
              integer(entry.get("amount"), "$.chemicals." + field + "[" + index + "].amount"),
              1,
              Long.MAX_VALUE));
    }
  }

  private static void demands(JsonElement element, String path, boolean input) {
    if (element == null || !element.isJsonArray()) {
      throw error(path, "Expected array");
    }
    var array = element.getAsJsonArray();
    if (array.size() > MAX_DEMANDS) {
      throw error(path, "Expected at most " + MAX_DEMANDS + " requirements");
    }
    for (int i = 0; i < array.size(); i++) {
      String at = path + "[" + i + "]";
      var demand = object(array.get(i), at);
      fields(
          demand,
          Set.of("stack", "amount", "chance", "selector", "match", "preview_stack", "parallel_unaffected"),
          at);
      fixedFlag(demand, at);
      String match = demand.has("match") ? string(demand.get("match"), at + ".match") : "exact";
      if (!match.equals("exact") && !match.equals("item")) {
        throw error(at + ".match", "Expected exact or item");
      }
      if (match.equals("item") && (!input || array.size() != 1)) {
        throw error(at + ".match", "Item-only matching requires a single item input row");
      }
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
      if (demand.has("preview_stack")) {
        var preview = object(demand.get("preview_stack"), at + ".preview_stack");
        fields(preview, Set.of("id", "components"), at + ".preview_stack");
        identifier(preview.get("id"), at + ".preview_stack.id");
        if (preview.has("components") && !preview.get("components").isJsonObject()) {
          throw error(at + ".preview_stack.components", "Expected component map");
        }
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

  private static void probability(JsonObject row, String path) {
    if (row.has("chance")) {
      double chance = decimal(row.get("chance"), path + ".chance");
      if (chance < 0 || chance > 1) {
        throw error(path + ".chance", "Expected a probability within 0..1");
      }
    }
  }

  private static void validateScriptOptions(JsonObject source) {
    if (source.has("item_callbacks")) {
      var callbacks = source.get("item_callbacks");
      if (!callbacks.isJsonArray()) {
        throw error("$.item_callbacks", "Expected a bounded callback array");
      }
      for (var entry : callbacks.getAsJsonArray()) {
        var binding = object(entry, "$.item_callbacks[]");
        fields(binding, Set.of("io", "row", "checker", "modifiers"), "$.item_callbacks[]");
        long row = integer(binding.get("row"), "$.item_callbacks[].row");
        if (row < 0 || row >= MachineRecipeJson.MAX_DEMANDS) {
          throw error("$.item_callbacks[].row", "Outside the item row range");
        }
      }
    }
    var options = RecipeScriptOptions.CODEC.codec().parse(JsonOps.INSTANCE, source).getOrThrow();
    options.validateRows(source.getAsJsonArray("inputs").size(), source.getAsJsonArray("outputs").size());
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

  private static void fixedFlag(JsonObject row, String path) {
    var value = row.get("parallel_unaffected");
    if (value != null && (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())) {
      throw error(path + ".parallel_unaffected", "Expected boolean");
    }
  }

  /** Native NBT booleans are bytes; restore only declared row flag fields. */
  static void restoreRowBooleans(JsonObject json, CompoundTag source) {
    for (String field : List.of("inputs", "outputs", "fluid_inputs", "fluid_outputs")) {
      if (!json.has(field)) {
        continue;
      }
      var rows = source.getList(field, Tag.TAG_COMPOUND);
      var encoded = json.getAsJsonArray(field);
      for (int index = 0; index < rows.size() && index < encoded.size(); index++) {
        var row = rows.getCompound(index);
        if (row.contains("parallel_unaffected")) {
          if (!row.contains("parallel_unaffected", Tag.TAG_BYTE)
              || row.getByte("parallel_unaffected") < 0
              || row.getByte("parallel_unaffected") > 1) {
            throw new IllegalArgumentException("Invalid frozen parallel-unaffected flag");
          }
          encoded.get(index).getAsJsonObject()
              .addProperty("parallel_unaffected", row.getBoolean("parallel_unaffected"));
        }
      }
    }
  }

  private static JsonParseException error(String path, String message) {
    return new JsonParseException(path + ": " + message);
  }
}
