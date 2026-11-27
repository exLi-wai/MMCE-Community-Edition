package com.lw.modularmachine.api.recipe.adapter;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.function.ToIntFunction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.HolderLookup;
import com.lw.modularmachine.api.recipe.FluidRequirement;
import com.lw.modularmachine.api.recipe.IngredientArrayData;
import com.lw.modularmachine.api.recipe.TriggeredResourceData;
import com.lw.modularmachine.api.recipe.resource.RecipeResources;
import com.lw.modularmachine.api.recipe.ItemAmount;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceBuilderDeclarations;
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
import net.minecraft.resources.ResourceLocation;
import com.lw.modularmachine.api.recipe.ItemPaymentData;

/**
 * Closed, immutable additions to each original declaration produced by an
 * adapter.
 */
public record RecipeAdapterCustomization(
    List<RecipeModifier> modifiers,
    List<SmartNumberRequirement> interfaceInputs,
    List<FluidRequirement> fluidInputs,
    List<FluidRequirement> fluidOutputs,
    Optional<FluidRequirement> fluidPerTick,
    List<ChemicalRequirement> chemicalInputs,
    List<ChemicalRequirement> chemicalOutputs,
    List<ChemicalRequirement> chemicalPerTick,
    List<ItemAmount> itemOutputs,
    Map<Phase, String> selectors,
    Optional<Boolean> parallelized,
    Optional<RecipeFailureAction> failureAction,
    long energyOutputPerTick,
    Optional<String> threadName,
    Optional<Integer> maxThreads,
    List<String> tooltips,
    List<CallbackBinding> itemCallbacks,
    List<ItemParallelism> itemParallelism,
    List<ChemicalRequirement> chemicalPerTickOutputs,
    List<FluidRequirement> fluidPerTickInputs,
    List<FluidRequirement> fluidPerTickOutputs,
    ResourceAdditions resourceAdditions) {
  public static final int MAX_REQUIREMENTS = 256;
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
      List.of(),
      List.of(),
      List.of(),
      List.of());

  public RecipeAdapterCustomization(List<RecipeModifier> modifiers, List<SmartNumberRequirement> interfaceInputs, List<FluidRequirement> fluidInputs, List<FluidRequirement> fluidOutputs, Optional<FluidRequirement> fluidPerTick, List<ChemicalRequirement> chemicalInputs, List<ChemicalRequirement> chemicalOutputs, List<ChemicalRequirement> chemicalPerTick, List<ItemAmount> itemOutputs, Map<Phase, String> selectors, Optional<Boolean> parallelized, Optional<RecipeFailureAction> failureAction, long energyOutputPerTick, Optional<String> threadName, Optional<Integer> maxThreads, List<String> tooltips, List<CallbackBinding> itemCallbacks, List<ItemParallelism> itemParallelism, List<ChemicalRequirement> chemicalPerTickOutputs, List<FluidRequirement> fluidPerTickInputs, List<FluidRequirement> fluidPerTickOutputs) {
    this(modifiers, interfaceInputs, fluidInputs, fluidOutputs, fluidPerTick, chemicalInputs, chemicalOutputs,
        chemicalPerTick, itemOutputs, selectors, parallelized, failureAction, energyOutputPerTick, threadName,
        maxThreads, tooltips, itemCallbacks, itemParallelism, chemicalPerTickOutputs, fluidPerTickInputs,
        fluidPerTickOutputs, ResourceAdditions.NONE);
  }

  public RecipeAdapterCustomization(List<RecipeModifier> modifiers, List<SmartNumberRequirement> interfaceInputs, List<FluidRequirement> fluidInputs, List<FluidRequirement> fluidOutputs, Optional<FluidRequirement> fluidPerTick, List<ChemicalRequirement> chemicalInputs, List<ChemicalRequirement> chemicalOutputs, List<ChemicalRequirement> chemicalPerTick, List<ItemAmount> itemOutputs, Map<Phase, String> selectors, Optional<Boolean> parallelized, Optional<RecipeFailureAction> failureAction, long energyOutputPerTick, Optional<String> threadName, Optional<Integer> maxThreads, List<String> tooltips, List<CallbackBinding> itemCallbacks, List<ItemParallelism> itemParallelism) {
    this(modifiers, interfaceInputs, fluidInputs, fluidOutputs, fluidPerTick, chemicalInputs, chemicalOutputs,
        chemicalPerTick, itemOutputs, selectors, parallelized, failureAction, energyOutputPerTick, threadName,
        maxThreads, tooltips, itemCallbacks, itemParallelism, List.of(), List.of(), List.of());
  }

  public RecipeAdapterCustomization(List<RecipeModifier> modifiers, List<SmartNumberRequirement> interfaceInputs, List<FluidRequirement> fluidInputs, List<FluidRequirement> fluidOutputs, Optional<FluidRequirement> fluidPerTick, List<ChemicalRequirement> chemicalInputs, List<ChemicalRequirement> chemicalOutputs, List<ChemicalRequirement> chemicalPerTick, List<ItemAmount> itemOutputs, Map<Phase, String> selectors, Optional<Boolean> parallelized, Optional<RecipeFailureAction> failureAction) {
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
    chemicalPerTickOutputs = bounded(chemicalPerTickOutputs);
    fluidPerTickInputs = tickFluids(fluidPerTickInputs);
    fluidPerTickOutputs = tickFluids(fluidPerTickOutputs);
    itemOutputs = bounded(itemOutputs);
    Objects.requireNonNull(resourceAdditions, "resourceAdditions");
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

  /**
   * Check the bounded component snapshots once when completing a script import.
   * Only the individual descriptor or row codecs are consulted; these sizes are
   * independent of the imported recipe count.
   */
  public void validateCaptureSize(HolderLookup.Provider registries) {
    var ops = registries.createSerializationContext(JsonOps.INSTANCE);
    for (var rows : List
        .of(fluidInputs, fluidOutputs, fluidPerTickInputs, fluidPerTickOutputs, fluidPerTick.stream().toList())) {
      for (var row : rows) {
        requireSize(encodedSize(FluidRequirement.CODEC, ops, row));
      }
    }
    var ranges = new ArrayList<String>();
    for (int index = 0; index < itemOutputs.size(); index++) {
      var row = itemOutputs.get(index);
      requireSize(encodedSize(ItemStack.SINGLE_ITEM_CODEC, ops, row.ingredientStack()));
      if (!row.previewStack().isEmpty()) {
        requireSize(encodedSize(ItemStack.SINGLE_ITEM_CODEC, ops, row.previewStack()));
      }
      if (row.maxAmount() > 0) {
        ranges.add("{\"row\":" + index + ",\"min\":" + row.minAmount() + ",\"max\":" + row.maxAmount() + "}");
      }
    }
    var extra = resourceAdditions;
    var size = new CaptureSize();
    size.array("inputs", extra.inputs(), row -> encodedSize(ItemAmount.CODEC, ops, row));
    size.array(
        "ingredient_arrays",
        extra.groups().inputs(),
        row -> encodedSize(IngredientArrayData.Group.CODEC, ops, row));
    size.array(
        "random_item_outputs",
        extra.groups().randomOutputs(),
        row -> encodedSize(IngredientArrayData.Group.CODEC, ops, row));
    size.array(
        "triggered_resources",
        extra.triggers(),
        trigger -> encodedSize(TriggeredResourceData.Row.CODEC, ops, trigger.row()));
    size.array("output_ranges", ranges, String::length);
    extra.loadJei().ifPresent(value -> size.field("load_jei", Boolean.toString(value).length()));
    if (!extra.payments().isEmpty()) {
      size.field("item_payments", encodedSize(ItemPaymentData.CODEC, ops, extra.payments()));
    }
    for (var nativeResource : extra.nativeResources().entrySet()) {
      var fields = RecipeResourceBuilderDeclarations.encode(nativeResource.getValue().entry(), registries);
      size.add(nativeResource.getKey().toString().length() + fields.toString().length());
    }
  }

  private static final class CaptureSize {
    private long length = 2;
    private boolean hasFields;

    void add(long count) {
      length += count;
      requireSize(length);
    }

    void field(String name, long valueLength) {
      add(name.length() + 3 + valueLength + (hasFields ? 1 : 0));
      hasFields = true;
    }

    <T> void array(String name, List<T> values, ToIntFunction<T> measure) {
      if (!values.isEmpty()) {
        field(name, values.size() + 1L);
        values.forEach(value -> add(measure.applyAsInt(value)));
      }
    }
  }

  private static <T> int encodedSize(Codec<T> codec, DynamicOps<JsonElement> ops, T value) {
    return codec.encodeStart(ops, value).getOrThrow().toString().length();
  }

  private static void requireSize(long size) {
    if (size > 65_536) {
      throw new IllegalArgumentException("Adapter resource component data is too large");
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

  public record ResourceAdditions(
      List<ItemAmount> inputs,
      IngredientArrayData groups,
      ItemPaymentData payments,
      List<Trigger> triggers,
      Optional<Boolean> loadJei,
      Map<ResourceLocation, NativeResource> nativeResources) {
    public static final ResourceAdditions NONE = new ResourceAdditions(
        List.of(),
        IngredientArrayData.NONE,
        ItemPaymentData.NONE,
        List.of(),
        Optional.empty(),
        Map.of());

    public ResourceAdditions {
      inputs = bounded(inputs);
      triggers = bounded(triggers);
      Objects.requireNonNull(groups);
      Objects.requireNonNull(payments);
      Objects.requireNonNull(loadJei);
      nativeResources = Map.copyOf(nativeResources);
      if (nativeResources.size() > MAX_REQUIREMENTS) {
        throw new IllegalArgumentException("Too many adapter native resources");
      }
      nativeResources.forEach((id, value) -> {
        if (!id.equals(value.entry().type().id())) {
          throw new IllegalArgumentException("Adapter resource identity differs: " + id);
        }
      });
    }
  }

  /** Appended fluid triggers may inherit their target recipe's phase selector. */
  public record Trigger(TriggeredResourceData.Row row, boolean inheritsSelector) {
    public Trigger {
      Objects.requireNonNull(row);
      if (inheritsSelector && !row.resource().equals("fluid")) {
        throw new IllegalArgumentException("Only triggered fluid rows inherit the phase selector");
      }
    }
  }

  public record NativeResource(RecipeResources.Entry<?> entry, boolean replace) {
    public NativeResource {
      Objects.requireNonNull(entry);
      requireResource(entry);
    }
    private static <D> void requireResource(RecipeResources.Entry<D> entry) {
      RecipeResourceBuilderDeclarations.capture(entry.type(), entry.data());
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

  private static List<FluidRequirement> tickFluids(List<FluidRequirement> values) {
    var checked = bounded(values);
    for (var value : checked) {
      if (value.parallelUnaffected()) {
        throw new IllegalArgumentException("Per-tick fluid quantities always scale with instances");
      }
    }
    return checked;
  }
}
