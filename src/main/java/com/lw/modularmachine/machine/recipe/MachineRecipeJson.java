package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.recipe.MachineRecipeDeclaration;
import com.lw.modularmachine.api.recipe.IngredientArrayData;
import com.lw.modularmachine.api.recipe.TriggeredResourceData;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapLike;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.ByteTag;
import java.util.Optional;
import com.lw.modularmachine.api.recipe.RecipeSelectors;
import com.lw.modularmachine.api.recipe.RecipeFailureAction;
import com.lw.modularmachine.api.recipe.ChemicalRecipeData;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import com.mojang.serialization.JsonOps;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.api.smart.SmartNumberRequirement;
import com.lw.modularmachine.api.recipe.RecipeExecutionOptions;
import com.lw.modularmachine.api.recipe.RecipeScriptOptions;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import com.lw.modularmachine.api.recipe.ItemPaymentData;

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
      "load_jei",
      "item_callbacks",
      "item_payments",
      "fluid_per_tick",
      "fluid_per_tick_inputs",
      "fluid_per_tick_outputs",
      "fluid_inputs",
      "fluid_outputs",
      "failure_action",
      "parallelized",
      "random_seed",
      "modifiers",
      "interface_inputs",
      "ingredient_arrays",
      "random_item_outputs",
      "triggered_resources",
      "selectors",
      "chemicals",
      "inputs",
      "outputs",
      "neoforge:conditions");

  private MachineRecipeJson() {
  }

  public static Set<String> fields() {
    var fields = new HashSet<>(FIELDS);
    fields.addAll(RecipeResourceDefinitions.Fields.names());

    return Set.copyOf(fields);
  }

  public static MachineRecipeDeclaration decode(JsonObject root, HolderLookup.Provider registries) {
    return decode(root, registries.createSerializationContext(JsonOps.INSTANCE));
  }

  public static MachineRecipeDeclaration decode(JsonObject root, DynamicOps<JsonElement> ops) {
    return decode(root, ops, ops.getMap(root).getOrThrow());
  }

  static <T> MachineRecipeDeclaration decode(DynamicOps<T> ops, MapLike<T> input) {
    var mapped = ops.createMap(input.entries());
    var source = ops.convertTo(JsonOps.INSTANCE, mapped).getAsJsonObject();
    if (mapped instanceof CompoundTag tag) {
      restoreRowBooleans(source, tag);
      if (input.get("parallelized") instanceof ByteTag flag && flag.getAsByte() >= 0 && flag.getAsByte() <= 1) {
        source.addProperty("parallelized", flag.getAsByte() == 1);
      }
    }
    if (source.has(KUBEJS_CHANGED_MARKER)) {
      source = source.deepCopy();
      source.remove(KUBEJS_CHANGED_MARKER);
    }
    return decode(source, ops, input);
  }

  private static <T> MachineRecipeDeclaration decode(JsonObject source, DynamicOps<T> ops, MapLike<T> input) {
    return decode(source, ops, input, false);
  }

  /**
   * Group selections append exact rows after an already validated direct input.
   */
  @ApiStatus.Internal
  public static MachineRecipe compileResolved(JsonObject source, DynamicOps<JsonElement> ops) {
    var declaration = decode(source, ops, ops.getMap(source).getOrThrow(), true);
    return MachineRecipeCompiler
        .compileResolved(declaration, List.of(), false, MachineRecipeCompiler.currentDefaults());
  }

  private static <T> MachineRecipeDeclaration decode(JsonObject source, DynamicOps<T> ops, MapLike<T> input, boolean resolved) {
    // Install descriptor field ownership before the lightweight shape check on
    // first decode.
    RecipeResourceDefinitions.snapshot();
    var header = validate(source, resolved);
    return new MachineRecipeDeclaration(
        ResourceLocation.parse(header.machine()),
        header.priority(),
        header.duration(),
        RecipeResourceDefinitions.CODEC.decode(ops, input).getOrThrow(),
        source.has("failure_action") ? Optional.of(RecipeFailureAction.parse(header.failureAction())) : Optional
            .empty(),
        source.has("parallelized") ? Optional.of(header.parallelized()) : Optional.empty(),
        header.randomSeed(),
        source.has("interface_inputs") ? SmartNumberRequirement
            .parse(source.get("interface_inputs"), "$.interface_inputs") : List.of(),
        header.selectors(),
        RecipeExecutionOptions.MAP_CODEC.decode(ops, input).getOrThrow(),
        RecipeScriptOptions.CODEC.decode(ops, input).getOrThrow(),
        IngredientArrayData.MAP_CODEC.decode(ops, input).getOrThrow(),
        TriggeredResourceData.MAP_CODEC.decode(ops, input).getOrThrow(),
        source.has("modifiers") ? RecipeModifiers.parse(source.get("modifiers"), "$.modifiers") : List.of(),
        source.has("fluid_per_tick"),
        source.has("selectors") ? source.getAsJsonObject("selectors").keySet() : Set.of());
  }

  /**
   * Explicit data-pack export, preserving omitted defaults and original values.
   */
  public static JsonObject encode(MachineRecipeDeclaration source, HolderLookup.Provider registries) {
    var ops = registries.createSerializationContext(JsonOps.INSTANCE);
    var fields = ops.mapBuilder();
    fields = RecipeResourceDefinitions.CODEC.encode(source.resources(), ops, fields);
    fields = RecipeExecutionOptions.MAP_CODEC.encode(source.executionOptions(), ops, fields);
    fields = RecipeScriptOptions.CODEC.encode(source.scriptOptions(), ops, fields);
    fields = IngredientArrayData.MAP_CODEC.encode(source.ingredientArrays(), ops, fields);
    fields = TriggeredResourceData.MAP_CODEC.encode(source.triggeredResources(), ops, fields);
    var result = fields.build(ops.empty()).getOrThrow().getAsJsonObject();
    result.addProperty("type", TYPE);
    result.addProperty("machine", source.machineId().toString());
    result.addProperty("priority", source.priority());
    result.addProperty("duration", source.duration());
    source.parallelized().ifPresent(value -> result.addProperty("parallelized", value));
    source.failureAction().ifPresent(value -> result.addProperty("failure_action", value.serialized()));
    if (source.randomSeed() != 0) {
      result.addProperty("random_seed", source.randomSeed());
    }
    if (!source.interfaceInputs().isEmpty()) {
      result.add(
          "interface_inputs",
          SmartNumberRequirement.CODEC.listOf().encodeStart(ops, source.interfaceInputs()).getOrThrow());
    }
    if (!source.modifiers().isEmpty()) {
      result.add("modifiers", RecipeModifier.CODEC.listOf().encodeStart(ops, source.modifiers()).getOrThrow());
    }
    if (!source.declaredSelectorFields().isEmpty()) {
      var selectors = RecipeSelectors.CODEC.encodeStart(ops, source.selectors()).getOrThrow().getAsJsonObject();
      for (var field : source.declaredSelectorFields()) {
        if (!selectors.has(field)) {
          selectors.addProperty(field, "");
        }
      }
      result.add("selectors", selectors);
    }
    if (source.legacyFluidPerTick()) {
      result.add("fluid_per_tick", result.remove("fluid_per_tick_inputs").getAsJsonArray().get(0));
    }
    return result;
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

  /** Best-effort source attribution for declarations skipped during reload. */
  @ApiStatus.Internal
  public static ResourceLocation declaredMachine(JsonElement root) {
    if (root == null || !root.isJsonObject()) {
      return null;
    }
    try {
      return ResourceLocation.parse(identifier(root.getAsJsonObject().get("machine"), "$.machine"));
    } catch (JsonParseException invalid) {
      return null;
    }
  }

  public static Header validate(JsonElement root) {
    return validate(root, false);
  }

  static Header validate(JsonElement root, boolean resolved) {
    var object = object(root, "$");
    fields(object, fields(), "$");
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
    validateFluidList(object, "fluid_inputs", "Fluid inputs require schema 2 and an array");
    validateFluidList(object, "fluid_outputs", "Fluid outputs require an array");
    validateFluidList(object, "fluid_per_tick_inputs", "Per-tick fluid inputs require an array");
    validateFluidList(object, "fluid_per_tick_outputs", "Per-tick fluid outputs require an array");
    if (object.has("fluid_per_tick") && object.has("fluid_per_tick_inputs")) {
      throw error("$.fluid_per_tick", "Use either fluid_per_tick or fluid_per_tick_inputs");
    }
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
    List<RecipeModifier> modifiers = object.has("modifiers") ? RecipeModifiers
        .parse(object.get("modifiers"), "$.modifiers") : List.of();
    var payments = object.has("item_payments") ? ItemPaymentData.CODEC
        .parse(JsonOps.INSTANCE, object.get("item_payments")).getOrThrow() : ItemPaymentData.NONE;
    demands(object.get("inputs"), "$.inputs", true, payments, resolved);
    demands(object.get("outputs"), "$.outputs", false, ItemPaymentData.NONE, false);
    validateScriptOptions(object);
    for (var tool : payments.tools()) {
      if (tool.row() >= object.getAsJsonArray("inputs").size()) {
        throw error("$.item_payments.tools", "Tool payment refers to a missing input row");
      }
    }
    if (object.has("ingredient_arrays")) {
      RecipeIngredientArrayResolution.validate(object.get("ingredient_arrays"));
    }
    if (object.has("random_item_outputs")) {
      RecipeIngredientArrayResolution.validateRandomOutputs(object.get("random_item_outputs"));
    }
    RecipeTriggeredResources.validate(object, (int) duration);
    var chemicals = validateChemicals(object);
    // Energy modifiers can supply an otherwise missing side. Compilation checks the
    // effective resources.
    if (!RecipeResourceDefinitions.Fields.hasExtensions(object.keySet())
        && (object.getAsJsonArray("inputs").isEmpty()
            && (!object.has("ingredient_arrays")
                || object.getAsJsonArray("ingredient_arrays").asList().stream().allMatch(element -> {
                  var group = element.getAsJsonObject();
                  return group.has("optional") && group.get("optional").getAsBoolean();
                }))
            && !RecipeTriggeredResources.has(object, RecipeModifier.Io.INPUT)
            && !object.has("item_payments")
            && !object.has("fluid_inputs")
            && !object.has("fluid_per_tick")
            && !object.has("fluid_per_tick_inputs")
            && energyPerTick == 0
            && chemicals.inputs().isEmpty()
            && chemicals.perTick().isEmpty()
            && !modifiesEnergy(modifiers, RecipeModifier.Io.INPUT)
            || object.getAsJsonArray("outputs").isEmpty()
                && (!object.has("random_item_outputs") || object.getAsJsonArray("random_item_outputs").isEmpty())
                && !RecipeTriggeredResources.has(object, RecipeModifier.Io.OUTPUT)
                && !object.has("fluid_outputs")
                && !object.has("fluid_per_tick_outputs")
                && chemicals.outputs().isEmpty()
                && chemicals.perTickOutputs().isEmpty()
                && energyOutputPerTick == 0
                && !modifiesEnergy(modifiers, RecipeModifier.Io.OUTPUT))) {
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
        object.has("fluid_per_tick") || object.has("fluid_per_tick_inputs"),
        object.has("fluid_inputs"),
        object.has("fluid_outputs") || object.has("fluid_per_tick_outputs"),
        failureAction,
        parallelized,
        randomSeed,
        selectors);
  }

  private static boolean modifiesEnergy(List<RecipeModifier> modifiers, RecipeModifier.Io io) {
    return modifiers.stream().anyMatch(rule -> rule.target() == RecipeModifier.Target.ENERGY && rule.io() == io);
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
      fields(
          fluid,
          Set.of("id", "amount", "components", "chance", "parallel_unaffected", "selector", "ignore_output_check"),
          path);
      fixedFlag(fluid, path);
      outputCheckFlag(fluid, path, field.equals("fluid_outputs"));
      if (field.startsWith("fluid_per_tick")
          && fluid.has("parallel_unaffected")
          && fluid.get("parallel_unaffected").getAsBoolean()) {
        throw error(path + ".parallel_unaffected", "Per-tick fluids scale with every parallel instance");
      }
      if (fluid.has("selector")) {
        ResourceSelector.require(string(fluid.get("selector"), path + ".selector"));
      }
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
    fields(chemicals, Set.of("inputs", "outputs", "per_tick", "per_tick_outputs"), "$.chemicals");
    for (String field : List.of("inputs", "outputs", "per_tick", "per_tick_outputs")) {
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
        fields(value, Set.of("chemical", "amount", "selector", "chance", "ignore_output_check"), at);
        outputCheckFlag(value, at, field.equals("outputs"));
        identifier(value.get("chemical"), at + ".chemical");
        if (integer(value.get("amount"), at + ".amount") <= 0) {
          throw error(at + ".amount", "Expected a positive long chemical amount");
        }
        if (value.has("selector")) {
          ResourceSelector.require(string(value.get("selector"), at + ".selector"));
        }
        probability(value, at);
      }
    }
    try {
      return ChemicalRecipeData.CODEC.parse(JsonOps.INSTANCE, chemicals).getOrThrow();
    } catch (RuntimeException invalid) {
      throw new JsonParseException("$.chemicals: " + invalid.getMessage(), invalid);
    }
  }

  private static void demands(JsonElement element, String path, boolean input, ItemPaymentData payments, boolean resolved) {
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
          Set.of(
              "stack",
              "amount",
              "chance",
              "selector",
              "match",
              "preview_stack",
              "parallel_unaffected",
              "min_amount",
              "max_amount",
              "ignore_output_check"),
          at);
      fixedFlag(demand, at);
      outputCheckFlag(demand, at, !input);
      String match = demand.has("match") ? string(demand.get("match"), at + ".match") : "exact";
      if (!match.equals("exact") && !match.equals("item")) {
        throw error(at + ".match", "Expected exact or item");
      }
      if (match.equals("item") && (!input || !payments.allowsItemOnlyMatch(i, array.size()) && !(resolved && i == 0))) {
        throw error(at + ".match", "Item-only matching requires a single item input row");
      }
      if (demand.has("selector")) {
        ResourceSelector.require(string(demand.get("selector"), at + ".selector"));
      }
      long amount = integer(demand.get("amount"), at + ".amount");
      if (amount <= 0) {
        throw error(at + ".amount", "Expected positive long");
      }
      if (demand.has("min_amount") || demand.has("max_amount")) {
        long min = integer(demand.get("min_amount"), at + ".min_amount");
        long max = integer(demand.get("max_amount"), at + ".max_amount");
        if (min < 0 || min > max || max != amount) {
          throw error(at, "Item quantity range requires 0 <= minimum <= maximum = amount");
        }
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

  private static void outputCheckFlag(JsonObject row, String path, boolean supported) {
    var value = row.get("ignore_output_check");
    if (value == null) {
      return;
    }
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
      throw error(path + ".ignore_output_check", "Expected boolean");
    }
    if (value.getAsBoolean() && !supported) {
      throw error(path + ".ignore_output_check", "Only completion output rows support ignored capacity");
    }
  }

  /** Native NBT booleans are bytes; restore only declared row flag fields. */
  static void restoreRowBooleans(JsonObject json, CompoundTag source) {
    for (String field : List.of("ingredient_arrays", "random_item_outputs")) {
      if (json.has(field)) {
        var rows = source.getList(field, Tag.TAG_COMPOUND);
        var encoded = json.getAsJsonArray(field);
        for (int index = 0; index < rows.size() && index < encoded.size(); index++) {
          var row = rows.getCompound(index);
          var group = encoded.get(index).getAsJsonObject();
          restoreBoolean(group, row, "optional");
          restoreBoolean(group, row, "parallel_unaffected");
          if (group.has("recipe_modifiers")) {
            var modifiers = row.getList("recipe_modifiers", Tag.TAG_COMPOUND);
            var rules = group.getAsJsonArray("recipe_modifiers");
            for (int rule = 0; rule < modifiers.size() && rule < rules.size(); rule++) {
              restoreBoolean(rules.get(rule).getAsJsonObject(), modifiers.getCompound(rule), "affect_chance");
            }
          }
        }
      }
    }
    if (source.contains("load_jei")) {
      if (!source.contains("load_jei", Tag.TAG_BYTE)
          || source.getByte("load_jei") < 0
          || source.getByte("load_jei") > 1) {
        throw new IllegalArgumentException("Invalid recipe display visibility flag");
      }
      json.addProperty("load_jei", source.getBoolean("load_jei"));
    }
    if (json.has("item_payments") && json.getAsJsonObject("item_payments").has("fuels")) {
      var fuels = source.getCompound("item_payments").getList("fuels", Tag.TAG_COMPOUND);
      var encoded = json.getAsJsonObject("item_payments").getAsJsonArray("fuels");
      for (int index = 0; index < fuels.size() && index < encoded.size(); index++) {
        var fuel = fuels.getCompound(index);
        if (fuel.contains("parallel_unaffected")) {
          if (!fuel.contains("parallel_unaffected", Tag.TAG_BYTE)
              || fuel.getByte("parallel_unaffected") < 0
              || fuel.getByte("parallel_unaffected") > 1) {
            throw new IllegalArgumentException("Invalid fuel parallel-unaffected flag");
          }
          encoded.get(index).getAsJsonObject()
              .addProperty("parallel_unaffected", fuel.getBoolean("parallel_unaffected"));
        }
      }
    }
    for (String field : List.of(
        "inputs",
        "outputs",
        "fluid_inputs",
        "fluid_outputs",
        "fluid_per_tick_inputs",
        "fluid_per_tick_outputs",
        "triggered_resources")) {
      if (!json.has(field)) {
        continue;
      }
      var rows = source.getList(field, Tag.TAG_COMPOUND);
      var encoded = json.getAsJsonArray(field);
      for (int index = 0; index < rows.size() && index < encoded.size(); index++) {
        var row = rows.getCompound(index);
        restoreBoolean(encoded.get(index).getAsJsonObject(), row, "ignore_output_check");
        if (field.equals("triggered_resources") && row.contains("repeatable")) {
          if (!row.contains("repeatable", Tag.TAG_BYTE)
              || row.getByte("repeatable") < 0
              || row.getByte("repeatable") > 1) {
            throw new IllegalArgumentException("Invalid trigger repeatable flag");
          }
          encoded.get(index).getAsJsonObject().addProperty("repeatable", row.getBoolean("repeatable"));
        }
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
    if (json.has("chemicals")) {
      var chemicals = source.getCompound("chemicals");
      var encoded = json.getAsJsonObject("chemicals");
      for (String field : List.of("inputs", "outputs", "per_tick", "per_tick_outputs")) {
        if (!encoded.has(field)) {
          continue;
        }
        var rows = chemicals.getList(field, Tag.TAG_COMPOUND);
        for (int index = 0; index < rows.size() && index < encoded.getAsJsonArray(field).size(); index++) {
          restoreBoolean(
              encoded.getAsJsonArray(field).get(index).getAsJsonObject(),
              rows.getCompound(index),
              "ignore_output_check");
        }
      }
    }
  }

  private static void restoreBoolean(JsonObject json, CompoundTag source, String field) {
    if (!source.contains(field)) {
      return;
    }
    if (!source.contains(field, Tag.TAG_BYTE) || source.getByte(field) < 0 || source.getByte(field) > 1) {
      throw new IllegalArgumentException("Invalid frozen recipe flag: " + field);
    }
    json.addProperty(field, source.getBoolean(field));
  }

  private static JsonParseException error(String path, String message) {
    return new JsonParseException(path + ": " + message);
  }
}
