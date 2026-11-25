package com.lw.modularmachine.machine.recipe.adapter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lw.modularmachine.api.recipe.ChemicalRequirement;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.recipe.RecipeScriptOptions;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterCustomization;
import com.lw.modularmachine.api.smart.SmartNumberRequirement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import org.jetbrains.annotations.ApiStatus;
import net.minecraft.resources.ResourceLocation;

/**
 * Internal merge into an adapter's original resource before native validation
 * and normalization.
 */
@ApiStatus.Internal
final class RecipeAdapterCustomizations {
  private RecipeAdapterCustomizations() {
  }

  static JsonObject apply(JsonObject original, RecipeAdapterCustomization customization, HolderLookup.Provider registries) {
    if (customization.equals(RecipeAdapterCustomization.NONE)) {
      return original;
    }
    var result = original.deepCopy();
    if (!result.has("inputs")) {
      result.add("inputs", new JsonArray());
    }
    if (!result.has("outputs")) {
      result.add("outputs", new JsonArray());
    }
    var additions = JsonParser.parseString(customization.resourceAdditions().encoded()).getAsJsonObject();
    int originalInputRows = result.getAsJsonArray("inputs").size();
    if (additions.has("item_payments")) {
      var payments = result.has("item_payments") ? result.getAsJsonObject("item_payments") : new JsonObject();
      var addedPayments = additions.getAsJsonObject("item_payments").deepCopy();
      if (addedPayments.has("tools")) {
        for (var element : addedPayments.getAsJsonArray("tools")) {
          var tool = element.getAsJsonObject();
          tool.addProperty("row", Math.addExact(originalInputRows, tool.get("row").getAsInt()));
        }
        append(payments, "tools", addedPayments.getAsJsonArray("tools"));
      }
      if (addedPayments.has("fuels")) {
        append(payments, "fuels", addedPayments.getAsJsonArray("fuels"));
      }
      result.add("item_payments", payments);
    }
    for (String field : List.of("inputs", "ingredient_arrays", "random_item_outputs")) {
      if (additions.has(field)) {
        append(result, field, additions.getAsJsonArray(field));
      }
    }
    if (additions.has("load_jei")) {
      result.add("load_jei", additions.get("load_jei"));
    }
    int originalOutputRows = result.getAsJsonArray("outputs").size();
    for (var parallel : customization.itemParallelism()) {
      var rows = result.getAsJsonArray(parallel.io() == RecipeModifier.Io.INPUT ? "inputs" : "outputs");
      if (parallel.row() >= rows.size()) {
        throw new IllegalArgumentException("Adapter parallel flag refers to a missing source item row");
      }
      rows.get(parallel.row()).getAsJsonObject().addProperty("parallel_unaffected", parallel.unaffected());
    }
    if (customization.energyOutputPerTick() > 0) {
      long originalEnergy = result.has("energy_output_per_tick") ? result.get("energy_output_per_tick").getAsLong() : 0;
      result.addProperty("energy_output_per_tick", Math.addExact(originalEnergy, customization.energyOutputPerTick()));
    }
    append(result, "modifiers", encode(RecipeModifier.CODEC, customization.modifiers()));
    append(result, "interface_inputs", encode(SmartNumberRequirement.CODEC, customization.interfaceInputs()));
    append(result, "fluid_inputs", fluids(customization.fluidInputs()));
    append(result, "fluid_outputs", fluids(customization.fluidOutputs()));
    customization.fluidPerTick().ifPresent(fluid -> addFluidPerTick(result, fluid));
    if (result.has("fluid_per_tick") && !customization.fluidPerTickInputs().isEmpty()) {
      var prior = new JsonArray();
      prior.add(result.remove("fluid_per_tick"));
      append(result, "fluid_per_tick_inputs", prior);
    }
    append(result, "fluid_per_tick_inputs", fluids(customization.fluidPerTickInputs()));
    append(result, "fluid_per_tick_outputs", fluids(customization.fluidPerTickOutputs()));
    if (!customization.chemicalInputs().isEmpty()
        || !customization.chemicalOutputs().isEmpty()
        || !customization.chemicalPerTick().isEmpty()
        || !customization.chemicalPerTickOutputs().isEmpty()) {
      var chemicals = result.has("chemicals") ? result.getAsJsonObject("chemicals") : new JsonObject();
      append(chemicals, "inputs", encode(ChemicalRequirement.CODEC, customization.chemicalInputs()));
      append(chemicals, "outputs", encode(ChemicalRequirement.CODEC, customization.chemicalOutputs()));
      append(chemicals, "per_tick", encode(ChemicalRequirement.CODEC, customization.chemicalPerTick()));
      append(chemicals, "per_tick_outputs", encode(ChemicalRequirement.CODEC, customization.chemicalPerTickOutputs()));
      result.add("chemicals", chemicals);
    }
    var outputs = new JsonArray();
    for (var output : customization.itemOutputs()) {
      var row = new JsonObject();
      row.add("stack", JsonParser.parseString(output.encodedStack()));
      row.addProperty("amount", output.amount());
      if (output.chance() != 1) {
        row.addProperty("chance", output.chance());
      }
      if (!output.selector().isEmpty()) {
        row.addProperty("selector", output.selector());
      }
      if (output.parallelUnaffected()) {
        row.addProperty("parallel_unaffected", true);
      }
      output.encodedPreview().ifPresent(preview -> row.add("preview_stack", JsonParser.parseString(preview)));
      outputs.add(row);
    }
    append(result, "outputs", outputs);
    if (additions.has("output_ranges")) {
      for (var range : additions.getAsJsonArray("output_ranges")) {
        var value = range.getAsJsonObject();
        int index = Math.addExact(originalOutputRows, value.get("row").getAsInt());
        var row = result.getAsJsonArray("outputs").get(index).getAsJsonObject();
        row.add("min_amount", value.get("min"));
        row.add("max_amount", value.get("max"));
        row.add("amount", value.get("max"));
      }
    }
    if (!customization.selectors().isEmpty()) {
      var selectors = result.has("selectors") ? result.getAsJsonObject("selectors") : new JsonObject();
      customization.selectors().forEach((phase, selector) -> {
        if (selectors.has(phase.field()) && !selectors.get(phase.field()).getAsString().equals(selector)) {
          throw new IllegalArgumentException("Adapter customization conflicts with the source phase selector");
        }
        selectors.addProperty(phase.field(), selector);
      });
      result.add("selectors", selectors);
    }
    if (additions.has("triggered_resources")) {
      var triggers = additions.getAsJsonArray("triggered_resources");
      var selectors = result.getAsJsonObject("selectors");
      if (selectors != null) {
        for (var element : triggers) {
          var row = element.getAsJsonObject();
          if (row.get("resource").getAsString().equals("fluid") && !row.has("selector")) {
            var selector = selectors.get("fluid_" + row.get("io").getAsString());
            if (selector != null) {
              row.add("selector", selector);
            }
          }
        }
      }
      append(result, "triggered_resources", triggers);
    }
    customization.parallelized().ifPresent(value -> result.addProperty("parallelized", value));
    customization.failureAction().ifPresent(value -> result.addProperty("failure_action", value.serialized()));
    customization.threadName().ifPresent(value -> result.addProperty("thread_name", value));
    customization.maxThreads().ifPresent(value -> result.addProperty("max_threads", value));
    addScriptOptions(result, customization, originalOutputRows);
    resolveItemOnlySource(result);
    var nativeResources = customization.resourceAdditions().nativeResources();
    if (!nativeResources.isEmpty()) {
      RecipeAdapterNativeResources.apply(result, nativeResources, registries);
    }

    return result;
  }

  private static void resolveItemOnlySource(JsonObject result) {
    var inputs = result.getAsJsonArray("inputs");
    if (inputs.isEmpty() || inputs.size() == 1 && !result.has("ingredient_arrays")) {
      return;
    }
    var source = inputs.get(0).getAsJsonObject();
    if (!source.has("match") || !source.get("match").getAsString().equals("item")) {
      return;
    }
    var choice = new JsonObject();
    choice.addProperty("resource", "item");
    choice.add("item", source.get("stack"));
    choice.addProperty("match", "item");
    for (String field : List.of("amount", "chance", "min_amount", "max_amount")) {
      if (source.has(field)) {
        choice.add(field, source.get(field));
      }
    }
    if (result.has("item_callbacks")) {
      var retained = new JsonArray();
      for (var element : result.getAsJsonArray("item_callbacks")) {
        var binding = element.getAsJsonObject();
        if (binding.get("io").getAsString().equals("input")) {
          int index = binding.get("row").getAsInt();
          if (index == 0) {
            for (String field : List.of("checker", "modifiers")) {
              if (binding.has(field)) {
                choice.add(field, binding.get(field));
              }
            }
            continue;
          }
          binding.addProperty("row", index - 1);
        }
        retained.add(binding);
      }
      result.add("item_callbacks", retained);
    }
    if (result.has("item_payments") && result.getAsJsonObject("item_payments").has("tools")) {
      for (var element : result.getAsJsonObject("item_payments").getAsJsonArray("tools")) {
        var tool = element.getAsJsonObject();
        int row = tool.get("row").getAsInt();
        if (row == 0) {
          throw new IllegalArgumentException("An item-only source cannot be a durability payment");
        }
        tool.addProperty("row", row - 1);
      }
    }
    var alternatives = new JsonArray();
    alternatives.add(choice);
    var group = new JsonObject();
    group.add("alternatives", alternatives);
    for (String field : List.of("selector", "parallel_unaffected")) {
      if (source.has(field)) {
        group.add(field, source.get(field));
      }
    }
    var groups = new JsonArray();
    groups.add(group);
    append(result, "ingredient_arrays", groups);
    inputs.remove(0);
  }

  private static void addScriptOptions(JsonObject result, RecipeAdapterCustomization customization, int originalOutputRows) {
    var previous = RecipeScriptOptions.CODEC.codec().parse(JsonOps.INSTANCE, result).getOrThrow();
    var tooltips = new ArrayList<>(previous.tooltips());
    tooltips.addAll(customization.tooltips());
    var bindings = new LinkedHashMap<String, RecipeScriptOptions.ItemCallbacks>();
    previous.itemCallbacks().forEach(binding -> bindings.put(binding.io().serialized() + ":" + binding.row(), binding));
    for (var addition : customization.itemCallbacks()) {
      var callback = addition.callback();
      int row = addition.addedOutput() ? Math.addExact(originalOutputRows, callback.row()) : callback.row();
      var next = new RecipeScriptOptions.ItemCallbacks(callback.io(), row, callback.checker(), callback.modifiers());
      String key = next.io().serialized() + ":" + row;
      var old = bindings.get(key);
      if (old != null) {
        Optional<ResourceLocation> checker = old.checker();
        if (next.checker().isPresent()) {
          if (checker.isPresent() && !checker.equals(next.checker())) {
            throw new IllegalArgumentException("Adapter customization conflicts with a source item checker");
          }
          checker = next.checker();
        }
        var modifiers = new ArrayList<>(old.modifiers());
        modifiers.addAll(next.modifiers());
        next = new RecipeScriptOptions.ItemCallbacks(next.io(), row, checker, modifiers);
      }
      bindings.put(key, next);
    }
    var options = new RecipeScriptOptions(tooltips, List.copyOf(bindings.values()), previous.loadJei());
    options.validateRows(result.getAsJsonArray("inputs").size(), result.getAsJsonArray("outputs").size());
    var encoded = RecipeScriptOptions.CODEC.codec().encodeStart(JsonOps.INSTANCE, options).getOrThrow()
        .getAsJsonObject();
    encoded.entrySet().forEach(entry -> result.add(entry.getKey(), entry.getValue()));
  }

  private static void addFluidPerTick(JsonObject result, RecipeAdapterCustomization.FluidResource addition) {
    var fluid = JsonParser.parseString(addition.encoded()).getAsJsonObject();
    if (result.has("fluid_per_tick")) {
      var previous = result.getAsJsonObject("fluid_per_tick");
      var previousKey = previous.deepCopy();
      var nextKey = fluid.deepCopy();
      previousKey.remove("amount");
      nextKey.remove("amount");
      if (!previousKey.equals(nextKey)) {
        throw new IllegalArgumentException("Adapter cannot add a different fluid-per-tick resource");
      }
      fluid.addProperty("amount", Math.addExact(previous.get("amount").getAsInt(), fluid.get("amount").getAsInt()));
    }
    result.add("fluid_per_tick", fluid);
  }

  private static JsonArray fluids(List<RecipeAdapterCustomization.FluidResource> resources) {
    var rows = new JsonArray();
    resources.forEach(resource -> rows.add(JsonParser.parseString(resource.encoded())));

    return rows;
  }

  private static <T> JsonArray encode(Codec<T> codec, List<T> values) {
    var rows = new JsonArray();
    values.forEach(value -> rows.add(codec.encodeStart(JsonOps.INSTANCE, value).getOrThrow()));

    return rows;
  }

  private static void append(JsonObject result, String field, JsonArray additions) {
    if (additions.isEmpty()) {
      return;
    }
    var rows = result.has(field) ? result.getAsJsonArray(field) : new JsonArray();
    for (JsonElement addition : additions) {
      rows.add(addition);
    }
    result.add(field, rows);
  }
}
