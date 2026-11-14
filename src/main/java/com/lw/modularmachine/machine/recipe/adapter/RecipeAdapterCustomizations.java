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

  static JsonObject apply(JsonObject original, RecipeAdapterCustomization customization) {
    if (customization.equals(RecipeAdapterCustomization.NONE)) {
      return original;
    }
    var result = original.deepCopy();
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
    if (!customization.chemicalInputs().isEmpty()
        || !customization.chemicalOutputs().isEmpty()
        || !customization.chemicalPerTick().isEmpty()) {
      var chemicals = result.has("chemicals") ? result.getAsJsonObject("chemicals") : new JsonObject();
      append(chemicals, "inputs", encode(ChemicalRequirement.CODEC, customization.chemicalInputs()));
      append(chemicals, "outputs", encode(ChemicalRequirement.CODEC, customization.chemicalOutputs()));
      append(chemicals, "per_tick", encode(ChemicalRequirement.CODEC, customization.chemicalPerTick()));
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
    customization.parallelized().ifPresent(value -> result.addProperty("parallelized", value));
    customization.failureAction().ifPresent(value -> result.addProperty("failure_action", value.serialized()));
    customization.threadName().ifPresent(value -> result.addProperty("thread_name", value));
    customization.maxThreads().ifPresent(value -> result.addProperty("max_threads", value));
    addScriptOptions(result, customization, originalOutputRows);

    return result;
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
    var options = new RecipeScriptOptions(tooltips, List.copyOf(bindings.values()));
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
