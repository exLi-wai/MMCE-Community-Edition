package com.lw.modularmachine.api.recipe.adapter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lw.modularmachine.api.recipe.ChemicalRequirement;
import com.lw.modularmachine.api.recipe.RecipeFailureAction;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.recipe.RecipeExecutionOptions;
import com.lw.modularmachine.api.recipe.RecipeScriptOptions;
import com.lw.modularmachine.api.smart.SmartNumberRequirement;
import com.lw.modularmachine.machine.recipe.ResourceSelector;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/**
 * Closed, immutable additions to each original declaration produced by an
 * adapter.
 */
public record RecipeAdapterCustomization(
    List<RecipeModifier> modifiers,
    List<SmartNumberRequirement> interfaceInputs,
    List<FluidResource> fluidInputs,
    List<FluidResource> fluidOutputs,
    Optional<FluidResource> fluidPerTick,
    List<ChemicalRequirement> chemicalInputs,
    List<ChemicalRequirement> chemicalOutputs,
    List<ChemicalRequirement> chemicalPerTick,
    List<ItemOutput> itemOutputs,
    Map<Phase, String> selectors,
    Optional<Boolean> parallelized,
    Optional<RecipeFailureAction> failureAction,
    long energyOutputPerTick,
    Optional<String> threadName,
    Optional<Integer> maxThreads,
    List<String> tooltips,
    List<CallbackBinding> itemCallbacks,
    List<ItemParallelism> itemParallelism) {
  public static final int MAX_REQUIREMENTS = 256;
  private static final int MAX_RESOURCE_JSON = 65536;
  public static final RecipeAdapterCustomization NONE = new RecipeAdapterCustomization(
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      Optional.empty(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      Map.of(),
      Optional.empty(),
      Optional.empty(),
      0,
      Optional.empty(),
      Optional.empty(),
      List.of(),
      List.of(),
      List.of());

  public RecipeAdapterCustomization(List<RecipeModifier> modifiers, List<SmartNumberRequirement> interfaceInputs, List<FluidResource> fluidInputs, List<FluidResource> fluidOutputs, Optional<FluidResource> fluidPerTick, List<ChemicalRequirement> chemicalInputs, List<ChemicalRequirement> chemicalOutputs, List<ChemicalRequirement> chemicalPerTick, List<ItemOutput> itemOutputs, Map<Phase, String> selectors, Optional<Boolean> parallelized, Optional<RecipeFailureAction> failureAction) {
    this(modifiers, interfaceInputs, fluidInputs, fluidOutputs, fluidPerTick, chemicalInputs, chemicalOutputs,
        chemicalPerTick, itemOutputs, selectors, parallelized, failureAction, 0, Optional.empty(), Optional.empty(),
        List.of(), List.of(), List.of());
  }

  public RecipeAdapterCustomization {
    modifiers = bounded(modifiers);
    interfaceInputs = bounded(interfaceInputs);
    fluidInputs = bounded(fluidInputs);
    fluidOutputs = bounded(fluidOutputs);
    Objects.requireNonNull(fluidPerTick, "fluidPerTick");
    chemicalInputs = bounded(chemicalInputs);
    chemicalOutputs = bounded(chemicalOutputs);
    chemicalPerTick = bounded(chemicalPerTick);
    itemOutputs = bounded(itemOutputs);
    selectors = Map.copyOf(selectors);
    selectors.values().forEach(ResourceSelector::require);
    Objects.requireNonNull(parallelized, "parallelized");
    Objects.requireNonNull(failureAction, "failureAction");
    Objects.requireNonNull(threadName, "threadName");
    Objects.requireNonNull(maxThreads, "maxThreads");
    new RecipeExecutionOptions(threadName.orElse(""), maxThreads.orElse(0));
    tooltips = new RecipeScriptOptions(tooltips, List.of()).tooltips();
    itemCallbacks = bounded(itemCallbacks);
    itemParallelism = bounded(itemParallelism);
    if (energyOutputPerTick < 0) {
      throw new IllegalArgumentException("Adapter energy output cannot be negative");
    }
  }

  public enum Phase {
    FLUID_INPUT("fluid_input"),
    FLUID_OUTPUT("fluid_output"),
    FLUID_PER_TICK("fluid_per_tick"),
    ENERGY_INPUT("energy_input"),
    ENERGY_OUTPUT("energy_output");

    private final String field;

    Phase(String field) {
      this.field = field;
    }

    public String field() {
      return field;
    }
  }

  /**
   * Registry-codec JSON captured at registration; no mutable FluidStack is
   * retained.
   */
  public record FluidResource(String encoded) {
    public FluidResource {
      var object = resource(encoded, Set.of("id", "amount", "components", "chance", "parallel_unaffected"));
      identifier(object, "id");
      var amount = object.get("amount");
      if (amount == null || !amount.isJsonPrimitive() || !amount.getAsJsonPrimitive().isNumber()) {
        throw new IllegalArgumentException("An adapter fluid requires a positive integer amount");
      }
      int quantity = amount.getAsBigDecimal().intValueExact();
      if (quantity < 1) {
        throw new IllegalArgumentException("An adapter fluid requires a positive integer amount");
      }
      if (object.has("chance")) {
        var chance = object.get("chance");
        if (!chance.isJsonPrimitive()
            || !chance.getAsJsonPrimitive().isNumber()
            || !Double.isFinite(chance.getAsDouble())
            || chance.getAsDouble() < 0
            || chance.getAsDouble() > 1) {
          throw new IllegalArgumentException("Adapter fluid chance must be within 0..1");
        }
      }
      if (object.has("parallel_unaffected")
          && (!object.get("parallel_unaffected").isJsonPrimitive()
              || !object.get("parallel_unaffected").getAsJsonPrimitive().isBoolean())) {
        throw new IllegalArgumentException("Adapter fluid parallel flag requires a boolean");
      }
      encoded = object.toString();
    }
  }

  /**
   * A single-item prototype and a separate long quantity, with immutable
   * component data.
   */
  public record ItemOutput(
      String encodedStack,
      long amount,
      double chance,
      String selector,
      Optional<String> encodedPreview,
      boolean parallelUnaffected) {
    public ItemOutput(String encodedStack, long amount, double chance, String selector) {
      this(encodedStack, amount, chance, selector, Optional.empty(), false);
    }

    public ItemOutput(String encodedStack, long amount, double chance, String selector, Optional<String> encodedPreview) {
      this(encodedStack, amount, chance, selector, encodedPreview, false);
    }

    public ItemOutput {
      var object = resource(encodedStack, Set.of("id", "components"));
      identifier(object, "id");
      if (amount < 1 || !Double.isFinite(chance) || chance < 0 || chance > 1) {
        throw new IllegalArgumentException("Invalid adapter item output amount or chance");
      }
      ResourceSelector.require(selector);
      Objects.requireNonNull(encodedPreview, "encodedPreview");
      encodedPreview = encodedPreview.map(encoded -> {
        var preview = resource(encoded, Set.of("id", "components"));
        identifier(preview, "id");

        return preview.toString();
      });
      encodedStack = object.toString();
    }
  }

  /**
   * Output bindings may address this customization's appended rows before the
   * source row count is known.
   */
  public record CallbackBinding(RecipeScriptOptions.ItemCallbacks callback, boolean addedOutput) {
    public CallbackBinding {
      Objects.requireNonNull(callback, "callback");
      if (addedOutput && callback.io() != RecipeModifier.Io.OUTPUT) {
        throw new IllegalArgumentException("Relative adapter item callbacks require output rows");
      }
    }
  }

  /**
   * Explicitly edits the parallel flag of a source item row without changing its
   * actual resource.
   */
  public record ItemParallelism(RecipeModifier.Io io, int row, boolean unaffected) {
    public ItemParallelism {
      Objects.requireNonNull(io, "io");
      if (row < 0 || row >= 256) {
        throw new IllegalArgumentException("Invalid adapter source item parallel row");
      }
    }
  }

  private static <T> List<T> bounded(List<T> values) {
    var copy = List.copyOf(values);
    if (copy.size() > MAX_REQUIREMENTS) {
      throw new IllegalArgumentException("Too many adapter customization requirements");
    }

    return copy;
  }

  private static JsonObject resource(String encoded, Set<String> fields) {
    Objects.requireNonNull(encoded, "encoded resource");
    if (encoded.length() > MAX_RESOURCE_JSON) {
      throw new IllegalArgumentException("Adapter resource component data is too large");
    }
    var element = JsonParser.parseString(encoded);
    if (!element.isJsonObject() || !fields.containsAll(element.getAsJsonObject().keySet())) {
      throw new IllegalArgumentException("Adapter resource contains unsupported fields");
    }
    var object = element.getAsJsonObject();
    if (object.has("components") && !object.get("components").isJsonObject()) {
      throw new IllegalArgumentException("Adapter resource components require an object");
    }

    return object;
  }

  private static void identifier(JsonObject object, String field) {
    var value = object.get(field);
    if (value == null
        || !value.isJsonPrimitive()
        || !value.getAsJsonPrimitive().isString()
        || ResourceLocation.tryParse(value.getAsString()) == null) {
      throw new IllegalArgumentException("Adapter resource requires a valid " + field);
    }
  }
}
