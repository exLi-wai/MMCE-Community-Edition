package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.machine.recipe.RecipeScriptCallbacks;
import com.lw.modularmachine.util.math.ExactIntegers;
import com.lw.modularmachine.integration.kubejs.KubeJSAdapterCallbackBindings.CallbackRow;
import com.lw.modularmachine.integration.kubejs.KubeJSAdapterCallbackBindings.ModifierEntry;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterImport;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterCustomization;
import com.lw.modularmachine.api.recipe.ChemicalRequirement;
import com.lw.modularmachine.api.recipe.IngredientArrayData;
import com.lw.modularmachine.api.recipe.ItemPaymentData;
import com.lw.modularmachine.api.recipe.RecipeFailureAction;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.recipe.RecipeExecutionOptions;
import com.lw.modularmachine.api.recipe.RecipeScriptOptions;
import com.lw.modularmachine.api.recipe.RecipeItemCallbacks;
import com.lw.modularmachine.api.recipe.TriggeredResourceData;
import com.lw.modularmachine.api.smart.SmartNumberRequirement;
import com.lw.modularmachine.machine.recipe.ItemStackSnapshots;
import com.lw.modularmachine.machine.recipe.ResourceSelector;
import com.lw.modularmachine.machine.recipe.adapter.RecipeAdapterNativeResources;
import com.mojang.serialization.JsonOps;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.Comparator;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.fluids.FluidStack;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.ApiStatus;

/**
 * One CRT-style adapter import; its registration owns generation validity and
 * closing.
 */
@ApiStatus.Internal
public final class KubeJSRecipeAdapterBuilder extends KubeJSResourceRecipeBuilder<KubeJSRecipeAdapterBuilder> {
  private enum LastRequirement {
    NONE,
    ITEM_INPUT,
    FUEL,
    INGREDIENT_ARRAY,
    RANDOM_OUTPUT,
    ITEM_OUTPUT,
    FLUID_INPUT,
    FLUID_OUTPUT,
    FLUID_PER_TICK,
    FLUID_PER_TICK_OUTPUT,
    CHEMICAL,
    ENERGY,
    ENERGY_OUTPUT
  }

  private enum TriggerPhase {
    ITEM_INPUT("item", RecipeModifier.Io.INPUT),
    ITEM_OUTPUT("item", RecipeModifier.Io.OUTPUT),
    FLUID_INPUT("fluid", RecipeModifier.Io.INPUT),
    FLUID_OUTPUT("fluid", RecipeModifier.Io.OUTPUT),
    CHEMICAL_INPUT("chemical", RecipeModifier.Io.INPUT),
    CHEMICAL_OUTPUT("chemical", RecipeModifier.Io.OUTPUT);

    private final String resource;
    private final RecipeModifier.Io io;

    TriggerPhase(String resource, RecipeModifier.Io io) {
      this.resource = resource;
      this.io = io;
    }
  }

  private record TriggerTarget(TriggerPhase phase, int row) {
  }

  private record TriggerSettings(int tick, boolean repeatable) {
    private static final TriggerSettings NONE = new TriggerSettings(0, false);
  }

  private final KubeJSRecipeAdapterRegistration owner;
  private final ResourceLocation machine;
  private final ResourceLocation adapter;
  private String id;
  private boolean allRecipes = true;
  private List<ResourceLocation> recipes = List.of();
  private int duration;
  private long energyPerTick;
  private long energyOutputPerTick;
  private int energyRequirements;
  private int energyOutputRequirements;
  private int priority;
  private boolean prioritySpecified;
  private final List<RecipeModifier> modifiers = new ArrayList<>();
  private final List<SmartNumberRequirement> interfaceInputs = new ArrayList<>();
  private final List<RecipeAdapterCustomization.FluidResource> fluidInputs = new ArrayList<>(),
      fluidOutputs = new ArrayList<>();
  private final List<RecipeAdapterCustomization.FluidResource> fluidPerTickInputs = new ArrayList<>(),
      fluidPerTickOutputs = new ArrayList<>();
  private final List<ChemicalRequirement> chemicalInputs = new ArrayList<>(), chemicalOutputs = new ArrayList<>(),
      chemicalPerTick = new ArrayList<>(), chemicalPerTickOutputs = new ArrayList<>();
  private final List<RecipeAdapterCustomization.ItemOutput> itemOutputs = new ArrayList<>();
  private final List<JsonObject> itemInputs = new ArrayList<>();
  private final Map<Integer, Integer> durableInputs = new LinkedHashMap<>();
  private final List<ItemPaymentData.Fuel> fuelInputs = new ArrayList<>();
  private final List<IngredientArrayData.Group> ingredientArrays = new ArrayList<>(),
      randomItemOutputs = new ArrayList<>();
  private final List<KubeJSIngredientArrayBuilder.GroupCallbacks> arrayCallbacks = new ArrayList<>();
  private final Map<Integer, long[]> outputRanges = new LinkedHashMap<>();
  private Optional<Boolean> loadJei = Optional.empty();
  private final Map<RecipeAdapterCustomization.Phase, String> selectors = new EnumMap<>(
      RecipeAdapterCustomization.Phase.class);
  private Optional<Boolean> parallelized = Optional.empty();
  private Optional<RecipeFailureAction> failureAction = Optional.empty();
  private Optional<String> threadName = Optional.empty();
  private Optional<Integer> maxThreads = Optional.empty();
  private final List<String> tooltips = new ArrayList<>();
  private final KubeJSAdapterCallbackBindings callbacks = new KubeJSAdapterCallbackBindings();
  private final List<RecipeAdapterCustomization.ItemParallelism> itemParallelism = new ArrayList<>();
  private LastRequirement last = LastRequirement.NONE;
  private List<ChemicalRequirement> lastChemicals;
  private boolean built;
  private final Map<TriggerTarget, TriggerSettings> triggers = new LinkedHashMap<>();
  private JsonArray capturedTriggers;

  public KubeJSRecipeAdapterBuilder setTriggerTime(Number ticks) {
    requireMutable();
    int tick = (int) owner.integer(ticks, "trigger time", 0, Integer.MAX_VALUE);
    var target = triggerTarget();
    var previous = triggers.getOrDefault(target, TriggerSettings.NONE);
    triggers.put(target, new TriggerSettings(tick, previous.repeatable()));
    return this;
  }

  public KubeJSRecipeAdapterBuilder setTriggerRepeatable(boolean repeatable) {
    requireMutable();
    var target = triggerTarget();
    var previous = triggers.getOrDefault(target, TriggerSettings.NONE);
    triggers.put(target, new TriggerSettings(previous.tick(), repeatable));
    return this;
  }

  private TriggerTarget triggerTarget() {
    return switch (last) {
      case ITEM_INPUT -> new TriggerTarget(TriggerPhase.ITEM_INPUT, itemInputs.size() - 1);
      case ITEM_OUTPUT -> new TriggerTarget(TriggerPhase.ITEM_OUTPUT, itemOutputs.size() - 1);
      case FLUID_INPUT -> new TriggerTarget(TriggerPhase.FLUID_INPUT, fluidInputs.size() - 1);
      case FLUID_OUTPUT -> new TriggerTarget(TriggerPhase.FLUID_OUTPUT, fluidOutputs.size() - 1);
      case CHEMICAL -> {
        if (lastChemicals == chemicalPerTick || lastChemicals == chemicalPerTickOutputs) {
          throw owner.reject("Trigger time requires startup/final IO; per-tick rows already execute each tick");
        }
        yield new TriggerTarget(
            lastChemicals == chemicalInputs ? TriggerPhase.CHEMICAL_INPUT : TriggerPhase.CHEMICAL_OUTPUT,
            lastChemicals.size() - 1);
      }
      default -> throw owner.reject("Trigger time requires a last appended item, fluid or chemical startup/final row");
    };
  }

  private int[] prepareTriggeredResources() {
    var captured = new JsonArray();
    var removals = new HashMap<TriggerPhase, Set<Integer>>();
    for (var entry : triggers.entrySet()) {
      if (entry.getValue().tick() == 0) {
        continue;
      }
      captured.add(captureTriggeredRow(entry.getKey(), entry.getValue()));
      removals.computeIfAbsent(entry.getKey().phase(), ignored -> new HashSet<>()).add(entry.getKey().row());
    }
    var removedOutputs = removals.getOrDefault(TriggerPhase.ITEM_OUTPUT, Set.of());
    int[] outputRows = new int[itemOutputs.size()];
    int nextRow = 0;
    for (int index = 0; index < outputRows.length; index++) {
      outputRows[index] = removedOutputs.contains(index) ? -1 : nextRow++;
    }
    if (!removedOutputs.isEmpty() && !outputRanges.isEmpty()) {
      var retainedRanges = new LinkedHashMap<Integer, long[]>();
      outputRanges.forEach((row, range) -> retainedRanges.put(outputRows[row], range));
      outputRanges.clear();
      outputRanges.putAll(retainedRanges);
    }
    removals.forEach(this::removeTriggeredRows);
    capturedTriggers = captured;
    return outputRows;
  }

  private JsonObject captureTriggeredRow(TriggerTarget target, TriggerSettings timing) {
    if (duration > 0 && timing.tick() > duration) {
      throw owner.reject("Trigger time exceeds the explicit adapter duration");
    }
    var row = new JsonObject();
    row.addProperty("resource", target.phase().resource);
    row.addProperty("io", target.phase().io.serialized());
    row.addProperty("tick", timing.tick());
    row.addProperty("repeatable", timing.repeatable());
    var source = triggerSource(target, row);
    for (String field : List.of("amount", "chance", "selector", "parallel_unaffected", "match")) {
      if (source.has(field)) {
        row.add(field, source.get(field));
      }
    }
    TriggeredResourceData.Row.CODEC.parse(registries().createSerializationContext(JsonOps.INSTANCE), row).getOrThrow();
    return row;
  }

  private JsonObject triggerSource(TriggerTarget target, JsonObject row) {
    var phase = target.phase();
    int index = target.row();
    JsonObject source;
    if (phase == TriggerPhase.ITEM_INPUT) {
      source = itemInputs.get(index).deepCopy();
      if (source.has("min_amount") || durableInputs.containsKey(index)) {
        throw owner.reject("Triggered inputs cannot combine random quantities or durability payment");
      }
      row.add("item", source.remove("stack"));
    } else if (phase == TriggerPhase.ITEM_OUTPUT) {
      var output = itemOutputs.get(index);
      if (output.encodedPreview().isPresent()) {
        throw owner.reject("Triggered item rows cannot use an item preview");
      }
      if (outputRanges.containsKey(index)) {
        throw owner.reject("Triggered outputs cannot combine random quantities");
      }
      source = new JsonObject();
      source.addProperty("amount", output.amount());
      source.addProperty("chance", output.chance());
      source.addProperty("selector", output.selector());
      source.addProperty("parallel_unaffected", output.parallelUnaffected());
      row.add("item", JsonParser.parseString(output.encodedStack()));
    } else if (phase == TriggerPhase.FLUID_INPUT || phase == TriggerPhase.FLUID_OUTPUT) {
      source = JsonParser
          .parseString((phase == TriggerPhase.FLUID_INPUT ? fluidInputs : fluidOutputs).get(index).encoded())
          .getAsJsonObject();
      var fluid = source.deepCopy();
      fluid.remove("chance");
      fluid.remove("parallel_unaffected");
      fluid.addProperty("amount", 1);
      row.add("fluid", fluid);
      source.addProperty(
          "selector",
          selectors.getOrDefault(
              phase == TriggerPhase.FLUID_INPUT ? RecipeAdapterCustomization.Phase.FLUID_INPUT : RecipeAdapterCustomization.Phase.FLUID_OUTPUT,
              ""));
    } else {
      source = ChemicalRequirement.CODEC
          .encodeStart(
              JsonOps.INSTANCE,
              (phase == TriggerPhase.CHEMICAL_INPUT ? chemicalInputs : chemicalOutputs).get(index))
          .getOrThrow().getAsJsonObject();
      row.add("chemical", source.get("chemical"));
    }
    return source;
  }

  private void removeTriggeredRows(TriggerPhase phase, Set<Integer> removed) {
    if (phase == TriggerPhase.ITEM_INPUT && !durableInputs.isEmpty()) {
      var indexed = new LinkedHashMap<Integer, Integer>();
      durableInputs.forEach((index, damage) -> {
        if (removed.contains(index)) {
          throw owner.reject("A triggered input cannot also pay durability");
        }
        int shifted = index - (int) removed.stream().filter(row -> row < index).count();
        indexed.put(shifted, damage);
      });
      durableInputs.clear();
      durableInputs.putAll(indexed);
    }
    List<?> rows = switch (phase) {
      case ITEM_INPUT -> itemInputs;
      case ITEM_OUTPUT -> itemOutputs;
      case FLUID_INPUT -> fluidInputs;
      case FLUID_OUTPUT -> fluidOutputs;
      case CHEMICAL_INPUT -> chemicalInputs;
      case CHEMICAL_OUTPUT -> chemicalOutputs;
    };
    removed.stream().sorted(Comparator.reverseOrder()).forEach(index -> rows.remove((int) index));
  }

  private void addTriggeredResources(JsonObject additions) {
    if (!capturedTriggers.isEmpty()) {
      additions.add("triggered_resources", capturedTriggers.deepCopy());
    }
  }

  KubeJSRecipeAdapterBuilder(KubeJSRecipeAdapterRegistration owner, ResourceLocation machine, ResourceLocation adapter, String id) {
    this.owner = owner;
    this.machine = machine;
    this.adapter = adapter;
    this.id = id;
  }

  @Override
  void requireMutable() {
    owner.requireOpen();
    if (built || capturedTriggers != null) {
      throw owner.reject("Recipe adapter builder has already been finalized");
    }
  }

  @Override
  HolderLookup.Provider resourceRegistries() {
    return registries();
  }

  @Override
  void requireResourceOverride(ResourceLocation id) {
    RecipeAdapterNativeResources.requireCustomizable(id);
  }

  @Override
  KubeJSRecipeAdapterBuilder clearLastResourceRow() {
    last = LastRequirement.NONE;
    lastChemicals = null;
    return this;
  }

  public KubeJSRecipeAdapterBuilder addFuelItemInput(Number requiredTotalBurnTime) {
    return addFuel(owner.integer(requiredTotalBurnTime, "fuel burn time", 1, Long.MAX_VALUE));
  }

  public KubeJSRecipeAdapterBuilder addFuelItemInput(String requiredTotalBurnTime) {
    return addFuel(decimalInteger(requiredTotalBurnTime, "fuel burn time", 1));
  }

  private KubeJSRecipeAdapterBuilder addFuel(long burnTime) {
    requireMutable();
    add(fuelInputs, new ItemPaymentData.Fuel(burnTime, 1, false, ""), "fuel input");
    last = LastRequirement.FUEL;
    return this;
  }

  public KubeJSRecipeAdapterBuilder consumeDurability(Number durability) {
    requireMutable();
    if (last != LastRequirement.ITEM_INPUT) {
      throw owner.reject("consumeDurability requires the last appended item input");
    }
    int amount = (int) owner.integer(durability, "tool durability", 0, Integer.MAX_VALUE);
    var prototype = ItemStack.SINGLE_ITEM_CODEC
        .parse(registries().createSerializationContext(JsonOps.INSTANCE), itemInputs.getLast().get("stack"))
        .getOrThrow();
    if (amount > 0 && !prototype.isDamageableItem()) {
      throw owner.reject("consumeDurability requires a damageable item");
    }
    if (amount == 0) {
      durableInputs.remove(itemInputs.size() - 1);
    } else {
      durableInputs.put(itemInputs.size() - 1, amount);
    }
    return this;
  }

  private void addItemPayments(JsonObject additions) {
    var tools = durableInputs.entrySet().stream()
        .map(entry -> new ItemPaymentData.Tool(entry.getKey(), entry.getValue())).toList();
    var payments = new ItemPaymentData(tools, fuelInputs);
    if (!payments.isEmpty()) {
      additions.add("item_payments", ItemPaymentData.CODEC.encodeStart(JsonOps.INSTANCE, payments).getOrThrow());
    }
  }

  public KubeJSRecipeAdapterBuilder addEnergyPerTickInput(Number amount) {
    return addEnergy(owner.integer(amount, "FE/t", 0, Long.MAX_VALUE));
  }

  public KubeJSRecipeAdapterBuilder addEnergyPerTickInput(String amount) {
    return addEnergy(decimalInteger(amount, "FE/t", 0));
  }

  private KubeJSRecipeAdapterBuilder addEnergy(long addition) {
    requireMutable();
    if (!selectors.getOrDefault(RecipeAdapterCustomization.Phase.ENERGY_INPUT, "").isEmpty()) {
      throw owner.reject("Additional energy requirements cannot change a tagged energy cohort");
    }
    try {
      energyPerTick = Math.addExact(energyPerTick, addition);
      energyRequirements = Math.incrementExact(energyRequirements);
    } catch (ArithmeticException overflow) {
      throw owner.reject("Recipe adapter total FE/t exceeds the long range");
    }
    last = LastRequirement.ENERGY;

    return this;
  }

  public KubeJSRecipeAdapterBuilder addEnergyPerTickOutput(Number amount) {
    return addEnergyOutput(owner.integer(amount, "FE output/t", 0, Long.MAX_VALUE));
  }

  public KubeJSRecipeAdapterBuilder addEnergyPerTickOutput(String amount) {
    return addEnergyOutput(decimalInteger(amount, "FE output/t", 0));
  }

  private KubeJSRecipeAdapterBuilder addEnergyOutput(long amount) {
    requireMutable();
    if (!selectors.getOrDefault(RecipeAdapterCustomization.Phase.ENERGY_OUTPUT, "").isEmpty()) {
      throw owner.reject("Additional energy outputs cannot change a tagged energy cohort");
    }
    try {
      energyOutputPerTick = Math.addExact(energyOutputPerTick, amount);
      energyOutputRequirements = Math.incrementExact(energyOutputRequirements);
    } catch (ArithmeticException overflow) {
      throw owner.reject("Recipe adapter total FE output/t exceeds the long range");
    }
    last = LastRequirement.ENERGY_OUTPUT;

    return this;
  }

  public KubeJSRecipeAdapterBuilder addModifier(RecipeModifier modifier) {
    requireMutable();
    add(modifiers, modifier, "modifier");

    return this;
  }

  public KubeJSRecipeAdapterBuilder addModifiers(List<RecipeModifier> values) {
    requireMutable();
    requireList(values, "modifiers");
    values.forEach(this::addModifier);

    return this;
  }

  public KubeJSRecipeAdapterBuilder addSmartInterfaceDataInput(String type, Number minimum, Number maximum) {
    requireMutable();
    try {
      add(
          interfaceInputs,
          new SmartNumberRequirement(type, minimum.doubleValue(), maximum.doubleValue()),
          "smart interface");
    } catch (RuntimeException invalid) {
      throw owner.reject("Invalid recipe adapter smart interface requirement: " + invalid.getMessage());
    }
    last = LastRequirement.NONE;

    return this;
  }

  public KubeJSRecipeAdapterBuilder addSmartInterfaceDataInput(String type, Number value) {
    return addSmartInterfaceDataInput(type, value, value);
  }

  public KubeJSRecipeAdapterBuilder addFluidInput(FluidStack fluid) {
    requireMutable();
    add(fluidInputs, captureFluid(fluid), "fluid input");
    last = LastRequirement.FLUID_INPUT;

    return this;
  }

  public KubeJSRecipeAdapterBuilder addFluidInputs(List<FluidStack> fluids) {
    requireMutable();
    requireList(fluids, "fluid inputs");
    fluids.forEach(this::addFluidInput);
    last = LastRequirement.NONE;
    last = LastRequirement.NONE;

    return this;
  }

  public KubeJSRecipeAdapterBuilder addFluidOutput(FluidStack fluid) {
    requireMutable();
    add(fluidOutputs, captureFluid(fluid), "fluid output");
    last = LastRequirement.FLUID_OUTPUT;

    return this;
  }

  public KubeJSRecipeAdapterBuilder addFluidOutputs(List<FluidStack> fluids) {
    requireMutable();
    requireList(fluids, "fluid outputs");
    fluids.forEach(this::addFluidOutput);
    last = LastRequirement.NONE;
    last = LastRequirement.NONE;

    return this;
  }

  public KubeJSRecipeAdapterBuilder addFluidPerTickInput(FluidStack fluid) {
    requireMutable();
    add(fluidPerTickInputs, captureFluid(fluid), "fluid-per-tick input");
    last = LastRequirement.FLUID_PER_TICK;

    return this;
  }

  public KubeJSRecipeAdapterBuilder addFluidPerTickOutput(FluidStack fluid) {
    requireMutable();
    add(fluidPerTickOutputs, captureFluid(fluid), "fluid-per-tick output");
    last = LastRequirement.FLUID_PER_TICK_OUTPUT;

    return this;
  }

  public KubeJSRecipeAdapterBuilder addFluidPerTickInputs(List<FluidStack> fluids) {
    requireMutable();
    requireList(fluids, "fluid-per-tick inputs");
    fluids.forEach(this::addFluidPerTickInput);
    last = LastRequirement.NONE;

    return this;
  }

  public KubeJSRecipeAdapterBuilder addFluidPerTickOutputs(List<FluidStack> fluids) {
    requireMutable();
    requireList(fluids, "fluid-per-tick outputs");
    fluids.forEach(this::addFluidPerTickOutput);
    last = LastRequirement.NONE;

    return this;
  }

  public KubeJSRecipeAdapterBuilder addChemicalInput(String chemical, Number amount) {
    return addChemical(chemicalInputs, chemical, chemicalAmount(amount));
  }

  public KubeJSRecipeAdapterBuilder addChemicalInput(String chemical, String amount) {
    return addChemical(chemicalInputs, chemical, decimalInteger(amount, "chemical amount", 1));
  }

  public KubeJSRecipeAdapterBuilder addChemicalOutput(String chemical, Number amount) {
    return addChemical(chemicalOutputs, chemical, chemicalAmount(amount));
  }

  public KubeJSRecipeAdapterBuilder addChemicalOutput(String chemical, String amount) {
    return addChemical(chemicalOutputs, chemical, decimalInteger(amount, "chemical amount", 1));
  }

  public KubeJSRecipeAdapterBuilder addChemicalPerTickInput(String chemical, Number amount) {
    return addChemical(chemicalPerTick, chemical, chemicalAmount(amount));
  }

  public KubeJSRecipeAdapterBuilder addChemicalPerTickInput(String chemical, String amount) {
    return addChemical(chemicalPerTick, chemical, decimalInteger(amount, "chemical amount", 1));
  }

  public KubeJSRecipeAdapterBuilder addChemicalPerTickOutput(String chemical, Number amount) {
    return addChemical(chemicalPerTickOutputs, chemical, chemicalAmount(amount));
  }

  public KubeJSRecipeAdapterBuilder addChemicalPerTickOutput(String chemical, String amount) {
    return addChemical(chemicalPerTickOutputs, chemical, decimalInteger(amount, "chemical amount", 1));
  }

  public KubeJSRecipeAdapterBuilder addGasInput(String chemical, Number amount) {
    return addChemicalInput(chemical, amount);
  }

  public KubeJSRecipeAdapterBuilder addGasInput(String chemical, String amount) {
    return addChemicalInput(chemical, amount);
  }

  public KubeJSRecipeAdapterBuilder addGasOutput(String chemical, Number amount) {
    return addChemicalOutput(chemical, amount);
  }

  public KubeJSRecipeAdapterBuilder addGasOutput(String chemical, String amount) {
    return addChemicalOutput(chemical, amount);
  }

  public KubeJSRecipeAdapterBuilder addGasPerTickInput(String chemical, Number amount) {
    return addChemicalPerTickInput(chemical, amount);
  }

  public KubeJSRecipeAdapterBuilder addGasPerTickInput(String chemical, String amount) {
    return addChemicalPerTickInput(chemical, amount);
  }

  public KubeJSRecipeAdapterBuilder addGasPerTickOutput(String chemical, Number amount) {
    return addChemicalPerTickOutput(chemical, amount);
  }

  public KubeJSRecipeAdapterBuilder addGasPerTickOutput(String chemical, String amount) {
    return addChemicalPerTickOutput(chemical, amount);
  }

  public KubeJSRecipeAdapterBuilder addGasInput(ChemicalRequirement chemical) {
    return addChemical(chemicalInputs, chemical);
  }

  public KubeJSRecipeAdapterBuilder addGasOutput(ChemicalRequirement chemical) {
    return addChemical(chemicalOutputs, chemical);
  }

  public KubeJSRecipeAdapterBuilder addGasPerTickInput(ChemicalRequirement chemical) {
    return addChemical(chemicalPerTick, chemical);
  }

  public KubeJSRecipeAdapterBuilder addGasPerTickOutput(ChemicalRequirement chemical) {
    return addChemical(chemicalPerTickOutputs, chemical);
  }

  public KubeJSRecipeAdapterBuilder addGasInputs(List<ChemicalRequirement> chemicals) {
    return addChemicals(chemicalInputs, chemicals);
  }

  public KubeJSRecipeAdapterBuilder addGasOutputs(List<ChemicalRequirement> chemicals) {
    return addChemicals(chemicalOutputs, chemicals);
  }

  public KubeJSRecipeAdapterBuilder addGasPerTickInputs(List<ChemicalRequirement> chemicals) {
    return addChemicals(chemicalPerTick, chemicals);
  }

  public KubeJSRecipeAdapterBuilder addGasPerTickOutputs(List<ChemicalRequirement> chemicals) {
    return addChemicals(chemicalPerTickOutputs, chemicals);
  }

  public KubeJSRecipeAdapterBuilder addChemicalInput(ChemicalRequirement chemical) {
    return addGasInput(chemical);
  }

  public KubeJSRecipeAdapterBuilder addChemicalOutput(ChemicalRequirement chemical) {
    return addGasOutput(chemical);
  }

  public KubeJSRecipeAdapterBuilder addChemicalPerTickInput(ChemicalRequirement chemical) {
    return addGasPerTickInput(chemical);
  }

  public KubeJSRecipeAdapterBuilder addChemicalPerTickOutput(ChemicalRequirement chemical) {
    return addGasPerTickOutput(chemical);
  }

  public KubeJSRecipeAdapterBuilder addChemicalInputs(List<ChemicalRequirement> chemicals) {
    return addGasInputs(chemicals);
  }

  public KubeJSRecipeAdapterBuilder addChemicalOutputs(List<ChemicalRequirement> chemicals) {
    return addGasOutputs(chemicals);
  }

  public KubeJSRecipeAdapterBuilder addChemicalPerTickInputs(List<ChemicalRequirement> chemicals) {
    return addGasPerTickInputs(chemicals);
  }

  public KubeJSRecipeAdapterBuilder addChemicalPerTickOutputs(List<ChemicalRequirement> chemicals) {
    return addGasPerTickOutputs(chemicals);
  }

  private KubeJSRecipeAdapterBuilder addChemical(List<ChemicalRequirement> rows, ChemicalRequirement chemical) {
    requireMutable();
    add(rows, chemical, "chemical");
    lastChemicals = rows;
    last = LastRequirement.CHEMICAL;

    return this;
  }

  private KubeJSRecipeAdapterBuilder addChemicals(List<ChemicalRequirement> rows, List<ChemicalRequirement> chemicals) {
    requireMutable();
    requireList(chemicals, "chemicals");
    for (var chemical : chemicals) {
      addChemical(rows, chemical);
    }
    last = LastRequirement.NONE;
    lastChemicals = null;

    return this;
  }

  private KubeJSRecipeAdapterBuilder addChemical(List<ChemicalRequirement> rows, String chemical, long amount) {
    requireMutable();
    add(rows, new ChemicalRequirement(owner.identifier(chemical, "chemical"), amount, ""), "chemical");
    lastChemicals = rows;
    last = LastRequirement.CHEMICAL;

    return this;
  }

  public KubeJSRecipeAdapterBuilder addItemOutput(ItemStack stack) {
    return addItemOutput(stack, stack == null ? null : stack.getCount());
  }

  public KubeJSRecipeAdapterBuilder addItemInput(ItemStack stack) {
    return addItemInput(stack, stack == null ? null : stack.getCount());
  }

  public KubeJSRecipeAdapterBuilder addItemInput(ItemStack stack, Number amount) {
    return addItemInput(stack, owner.integer(amount, "item input amount", 1, Long.MAX_VALUE));
  }

  public KubeJSRecipeAdapterBuilder addItemInput(ItemStack stack, String amount) {
    return addItemInput(stack, decimalInteger(amount, "item input amount", 1));
  }

  private KubeJSRecipeAdapterBuilder addItemInput(ItemStack stack, long amount) {
    requireMutable();
    if (stack == null || stack.isEmpty() || itemInputs.size() >= RecipeAdapterCustomization.MAX_REQUIREMENTS) {
      throw owner.reject("Adapter item inputs require a nonempty stack and bounded row count");
    }
    var row = new JsonObject();
    row.add(
        "stack",
        ItemStack.SINGLE_ITEM_CODEC.encodeStart(
            owner.registries().createSerializationContext(JsonOps.INSTANCE),
            ItemStackSnapshots.detached(stack.copyWithCount(1), owner.registries())).getOrThrow());
    row.addProperty("amount", amount);
    itemInputs.add(row);
    last = LastRequirement.ITEM_INPUT;

    return this;
  }

  public KubeJSRecipeAdapterBuilder addItemInputs(List<ItemStack> stacks) {
    requireMutable();
    requireList(stacks, "item inputs");
    stacks.forEach(this::addItemInput);
    last = LastRequirement.NONE;

    return this;
  }

  public KubeJSRecipeAdapterBuilder addInput(ItemStack stack) {
    return addItemInput(stack);
  }

  public KubeJSRecipeAdapterBuilder addIngredientArrayInput(KubeJSIngredientArrayBuilder.IngredientArrayPrimer ingredients) {
    requireMutable();
    var group = ingredients.snapshot(owner.registries());
    add(ingredientArrays, group, "ingredient array");
    arrayCallbacks.add(ingredients.callbacksFor(group));
    last = LastRequirement.INGREDIENT_ARRAY;

    return this;
  }

  public KubeJSRecipeAdapterBuilder addCatalystInput(ItemStack ingredient, List<String> hints, List<RecipeModifier> rules) {
    return addCatalystInput(KubeJSIngredientArrayBuilder.newBuilder().addIngredient(ingredient), hints, rules);
  }

  public KubeJSRecipeAdapterBuilder addCatalystInput(FluidStack ingredient, List<String> hints, List<RecipeModifier> rules) {
    return addCatalystInput(KubeJSIngredientArrayBuilder.newBuilder().addIngredient(ingredient), hints, rules);
  }

  public KubeJSRecipeAdapterBuilder addCatalystInput(ChemicalRequirement ingredient, List<String> hints, List<RecipeModifier> rules) {
    return addCatalystInput(KubeJSIngredientArrayBuilder.newBuilder().addIngredient(ingredient), hints, rules);
  }

  public KubeJSRecipeAdapterBuilder addCatalystInput(KubeJSIngredientArrayBuilder.IngredientArrayPrimer ingredients, List<String> hints, List<RecipeModifier> rules) {
    addIngredientArrayInput(ingredients);
    var group = ingredientArrays.getLast();
    ingredientArrays.set(
        ingredientArrays.size() - 1,
        new IngredientArrayData.Group(
            group.alternatives(),
            group.chance(),
            !group.resource().equals("chemical"),
            group.selector(),
            true,
            hints,
            rules));

    return this;
  }

  public KubeJSRecipeAdapterBuilder addRandomItemOutput(KubeJSIngredientArrayBuilder.IngredientArrayPrimer ingredients) {
    requireMutable();
    var group = ingredients.snapshotForOutput(owner.registries());
    IngredientArrayData.validateRandomOutput(group);
    add(randomItemOutputs, group, "random output group");
    arrayCallbacks.add(ingredients.callbacksFor(group));
    last = LastRequirement.RANDOM_OUTPUT;

    return this;
  }

  public KubeJSRecipeAdapterBuilder setMinMaxAmount(Number minimum, Number maximum) {
    return setItemRange(
        owner.integer(minimum, "minimum item amount", 0, Long.MAX_VALUE),
        owner.integer(maximum, "maximum item amount", 1, Long.MAX_VALUE));
  }

  public KubeJSRecipeAdapterBuilder setMinMaxAmount(String minimum, String maximum) {
    return setItemRange(
        decimalInteger(minimum, "minimum item amount", 0),
        decimalInteger(maximum, "maximum item amount", 1));
  }

  private KubeJSRecipeAdapterBuilder setItemRange(long min, long max) {
    requireMutable();
    if (min > max) {
      throw owner.reject("Minimum item amount exceeds maximum");
    }
    if (last == LastRequirement.ITEM_INPUT) {
      var row = itemInputs.getLast();
      row.addProperty("amount", max);
      row.addProperty("min_amount", min);
      row.addProperty("max_amount", max);
    } else if (last == LastRequirement.ITEM_OUTPUT) {
      outputRanges.put(itemOutputs.size() - 1, new long[]{min, max});
    } else {
      throw owner.reject("setMinMaxAmount requires an appended item row");
    }

    return this;
  }

  public KubeJSRecipeAdapterBuilder setLoadJEI(boolean load) {
    requireMutable();
    loadJei = Optional.of(load);

    return this;
  }

  public KubeJSRecipeAdapterBuilder addItemOutput(ItemStack stack, Number amount) {
    return addItemOutput(stack, owner.integer(amount, "item output amount", 1, Long.MAX_VALUE));
  }

  public KubeJSRecipeAdapterBuilder addItemOutput(ItemStack stack, String amount) {
    return addItemOutput(stack, decimalInteger(amount, "item output amount", 1));
  }

  private KubeJSRecipeAdapterBuilder addItemOutput(ItemStack stack, long amount) {
    requireMutable();
    if (stack == null || stack.isEmpty()) {
      throw owner.reject("Recipe adapter item output must not be empty");
    }
    try {
      var registries = registries();
      var prototype = ItemStackSnapshots.detached(stack.copyWithCount(1), registries);
      var encoded = ItemStack.SINGLE_ITEM_CODEC
          .encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), prototype).getOrThrow();
      add(itemOutputs, new RecipeAdapterCustomization.ItemOutput(encoded.toString(), amount, 1, ""), "item output");
    } catch (RuntimeException invalid) {
      throw owner.reject("Invalid recipe adapter item output: " + invalid.getMessage());
    }
    last = LastRequirement.ITEM_OUTPUT;

    return this;
  }

  public KubeJSRecipeAdapterBuilder addItemOutputs(List<ItemStack> stacks) {
    requireMutable();
    requireList(stacks, "item outputs");
    stacks.forEach(this::addItemOutput);
    last = LastRequirement.NONE;

    return this;
  }

  public KubeJSRecipeAdapterBuilder setChance(Number chance) {
    requireMutable();
    if (chance == null
        || !Double.isFinite(chance.doubleValue())
        || chance.doubleValue() < 0
        || chance.doubleValue() > 1) {
      throw owner.reject("Adapter chance requires a value within 0..1");
    }
    if (last == LastRequirement.ITEM_INPUT) {
      itemInputs.getLast().addProperty("chance", chance.doubleValue());
    } else if (last == LastRequirement.FUEL) {
      var fuel = fuelInputs.getLast();
      fuelInputs.set(
          fuelInputs.size() - 1,
          new ItemPaymentData.Fuel(fuel.burnTime(), chance.doubleValue(), fuel.parallelUnaffected(), fuel.selector()));
    } else if (last == LastRequirement.RANDOM_OUTPUT) {
      KubeJSIngredientArrayBuilder.setLastGroupChance(randomItemOutputs, chance.doubleValue());
    } else if (last == LastRequirement.INGREDIENT_ARRAY) {
      KubeJSIngredientArrayBuilder.setLastGroupChance(ingredientArrays, chance.doubleValue());
    } else if (last == LastRequirement.ITEM_OUTPUT) {
      var previous = itemOutputs.getLast();
      itemOutputs.set(
          itemOutputs.size() - 1,
          new RecipeAdapterCustomization.ItemOutput(
              previous.encodedStack(),
              previous.amount(),
              chance.doubleValue(),
              previous.selector(),
              previous.encodedPreview(),
              previous.parallelUnaffected()));
    } else if (last == LastRequirement.CHEMICAL) {
      var previous = lastChemicals.getLast();
      lastChemicals.set(
          lastChemicals.size() - 1,
          new ChemicalRequirement(previous.chemical(), previous.amount(), previous.selector(), chance.doubleValue()));
    } else if (last == LastRequirement.FLUID_INPUT
        || last == LastRequirement.FLUID_OUTPUT
        || last == LastRequirement.FLUID_PER_TICK
        || last == LastRequirement.FLUID_PER_TICK_OUTPUT) {
      setLastFluidProperty("chance", new JsonPrimitive(chance.doubleValue()));
    } else {
      throw owner.reject("Adapter chance requires the last single item output, chemical or fluid row");
    }

    return this;
  }

  public KubeJSRecipeAdapterBuilder setTag(String selector) {
    requireMutable();
    try {
      ResourceSelector.require(selector);
    } catch (RuntimeException invalid) {
      throw owner.reject("Invalid adapter selector: " + invalid.getMessage());
    }
    switch (last) {
      case RANDOM_OUTPUT -> KubeJSIngredientArrayBuilder.setLastGroupSelector(randomItemOutputs, selector);
      case ITEM_INPUT -> itemInputs.getLast().addProperty("selector", selector);
      case FUEL -> {
        var fuel = fuelInputs.getLast();
        fuelInputs.set(
            fuelInputs.size() - 1,
            new ItemPaymentData.Fuel(fuel.burnTime(), fuel.chance(), fuel.parallelUnaffected(), selector));
      }
      case INGREDIENT_ARRAY -> KubeJSIngredientArrayBuilder.setLastGroupSelector(ingredientArrays, selector);
      case ITEM_OUTPUT -> {
        var previous = itemOutputs.getLast();
        itemOutputs.set(
            itemOutputs.size() - 1,
            new RecipeAdapterCustomization.ItemOutput(
                previous.encodedStack(),
                previous.amount(),
                previous.chance(),
                selector,
                previous.encodedPreview(),
                previous.parallelUnaffected()));
      }
      case CHEMICAL -> {
        var previous = lastChemicals.getLast();
        lastChemicals.set(
            lastChemicals.size() - 1,
            new ChemicalRequirement(previous.chemical(), previous.amount(), selector, previous.chance()));
      }
      case FLUID_INPUT -> cohort(RecipeAdapterCustomization.Phase.FLUID_INPUT, selector);
      case FLUID_OUTPUT -> cohort(RecipeAdapterCustomization.Phase.FLUID_OUTPUT, selector);
      case FLUID_PER_TICK, FLUID_PER_TICK_OUTPUT -> setLastFluidProperty("selector", new JsonPrimitive(selector));
      case ENERGY -> {
        if (energyRequirements != 1) {
          throw owner.reject("Adapter energy tags require one energy requirement");
        }
        cohort(RecipeAdapterCustomization.Phase.ENERGY_INPUT, selector);
      }
      case ENERGY_OUTPUT -> {
        if (energyOutputRequirements != 1) {
          throw owner.reject("Adapter energy output tags require one output requirement");
        }
        cohort(RecipeAdapterCustomization.Phase.ENERGY_OUTPUT, selector);
      }
      default -> throw owner.reject("Adapter tag requires a preceding resource requirement");
    }

    return this;
  }

  private void setLastFluidProperty(String property, JsonPrimitive value) {
    var rows = switch (last) {
      case FLUID_INPUT -> fluidInputs;
      case FLUID_OUTPUT -> fluidOutputs;
      case FLUID_PER_TICK -> fluidPerTickInputs;
      case FLUID_PER_TICK_OUTPUT -> fluidPerTickOutputs;
      default -> throw owner.reject("No last fluid requirement");
    };
    var encoded = JsonParser.parseString(rows.getLast().encoded()).getAsJsonObject();
    encoded.add(property, value);
    rows.set(rows.size() - 1, new RecipeAdapterCustomization.FluidResource(encoded.toString()));
  }

  public KubeJSRecipeAdapterBuilder setFluidInputTag(String selector) {
    return phase(RecipeAdapterCustomization.Phase.FLUID_INPUT, selector);
  }

  public KubeJSRecipeAdapterBuilder setFluidOutputTag(String selector) {
    return phase(RecipeAdapterCustomization.Phase.FLUID_OUTPUT, selector);
  }

  public KubeJSRecipeAdapterBuilder setFluidPerTickTag(String selector) {
    return phase(RecipeAdapterCustomization.Phase.FLUID_PER_TICK, selector);
  }

  public KubeJSRecipeAdapterBuilder setEnergyInputTag(String selector) {
    return phase(RecipeAdapterCustomization.Phase.ENERGY_INPUT, selector);
  }

  public KubeJSRecipeAdapterBuilder setEnergyOutputTag(String selector) {
    return phase(RecipeAdapterCustomization.Phase.ENERGY_OUTPUT, selector);
  }

  private KubeJSRecipeAdapterBuilder phase(RecipeAdapterCustomization.Phase phase, String selector) {
    requireMutable();
    try {
      cohort(phase, ResourceSelector.require(selector));
    } catch (RuntimeException invalid) {
      throw owner.reject("Invalid adapter phase selector: " + invalid.getMessage());
    }

    return this;
  }

  private void cohort(RecipeAdapterCustomization.Phase phase, String selector) {
    var previous = selectors.putIfAbsent(phase, selector);
    if (previous != null && !previous.equals(selector)) {
      throw owner.reject("Adapter fluid/energy selectors apply to the entire phase, not independent rows");
    }
  }

  public KubeJSRecipeAdapterBuilder setParallelized(boolean enabled) {
    requireMutable();
    parallelized = Optional.of(enabled);

    return this;
  }

  public KubeJSRecipeAdapterBuilder setParallelizeUnaffected(boolean enabled) {
    requireMutable();
    if (last == LastRequirement.RANDOM_OUTPUT) {
      KubeJSIngredientArrayBuilder.setLastGroupParallelUnaffected(randomItemOutputs, enabled);
    } else if (last == LastRequirement.INGREDIENT_ARRAY) {
      KubeJSIngredientArrayBuilder.setLastGroupParallelUnaffected(ingredientArrays, enabled);
    } else if (last == LastRequirement.ITEM_INPUT) {
      itemInputs.getLast().addProperty("parallel_unaffected", enabled);
    } else if (last == LastRequirement.FUEL) {
      var fuel = fuelInputs.getLast();
      fuelInputs.set(
          fuelInputs.size() - 1,
          new ItemPaymentData.Fuel(fuel.burnTime(), fuel.chance(), enabled, fuel.selector()));
    } else if (last == LastRequirement.ITEM_OUTPUT) {
      var previous = itemOutputs.getLast();
      itemOutputs.set(
          itemOutputs.size() - 1,
          new RecipeAdapterCustomization.ItemOutput(
              previous.encodedStack(),
              previous.amount(),
              previous.chance(),
              previous.selector(),
              previous.encodedPreview(),
              enabled));
    } else if (last == LastRequirement.FLUID_INPUT || last == LastRequirement.FLUID_OUTPUT) {
      setLastFluidProperty("parallel_unaffected", new JsonPrimitive(enabled));
    } else {
      throw owner
          .reject("Adapter parallel-unaffected applies only to the last single item or startup/output fluid row");
    }

    return this;
  }

  public KubeJSRecipeAdapterBuilder setItemInputParallelizeUnaffected(Number row, boolean enabled) {
    return itemParallelism(RecipeModifier.Io.INPUT, row, enabled);
  }

  public KubeJSRecipeAdapterBuilder setItemOutputParallelizeUnaffected(Number row, boolean enabled) {
    return itemParallelism(RecipeModifier.Io.OUTPUT, row, enabled);
  }

  private KubeJSRecipeAdapterBuilder itemParallelism(RecipeModifier.Io io, Number row, boolean enabled) {
    requireMutable();
    int index = (int) owner.integer(row, "source item parallel row", 0, 255);
    itemParallelism.removeIf(value -> value.io() == io && value.row() == index);
    add(
        itemParallelism,
        new RecipeAdapterCustomization.ItemParallelism(io, index, enabled),
        "source item parallel flags");

    return this;
  }

  public KubeJSRecipeAdapterBuilder setFailureAction(String action) {
    requireMutable();
    try {
      failureAction = Optional.of(RecipeFailureAction.parse(action));
    } catch (RuntimeException invalid) {
      throw owner.reject("Invalid adapter failure action: " + invalid.getMessage());
    }

    return this;
  }

  public KubeJSRecipeAdapterBuilder setThreadName(String name) {
    requireMutable();
    try {
      new RecipeExecutionOptions(name, maxThreads.orElse(0));
      threadName = Optional.of(name);
    } catch (RuntimeException invalid) {
      throw owner.reject("Invalid adapter thread name: " + invalid.getMessage());
    }

    return this;
  }

  public KubeJSRecipeAdapterBuilder setMaxThreads(Number limit) {
    requireMutable();
    int value = (int) owner.integer(limit, "maximum recipe threads", 0, RecipeExecutionOptions.MAX_THREADS);
    maxThreads = Optional.of(value);

    return this;
  }

  public KubeJSRecipeAdapterBuilder addRecipeTooltip(String... lines) {
    requireMutable();
    if (lines == null) {
      throw owner.reject("Adapter recipe tooltip lines must not be null");
    }

    return addRecipeTooltips(List.of(lines));
  }

  public KubeJSRecipeAdapterBuilder addRecipeTooltips(List<String> lines) {
    requireMutable();
    requireList(lines, "tooltip");
    var combined = new ArrayList<>(tooltips);
    combined.addAll(lines);
    try {
      var valid = new RecipeScriptOptions(combined, List.of()).tooltips();
      tooltips.clear();
      tooltips.addAll(valid);
    } catch (RuntimeException invalid) {
      throw owner.reject("Invalid adapter recipe tooltip: " + invalid.getMessage());
    }

    return this;
  }

  public KubeJSRecipeAdapterBuilder setPreviewStack(ItemStack preview) {
    requireMutable();
    if (last != LastRequirement.ITEM_OUTPUT || preview == null || preview.isEmpty()) {
      throw owner.reject("Adapter preview requires a last appended item output and a nonempty display stack");
    }
    try {
      var previous = itemOutputs.getLast();
      var registries = registries();
      var copy = ItemStackSnapshots.detached(preview.copyWithCount(1), registries);
      String encoded = ItemStack.SINGLE_ITEM_CODEC
          .encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), copy).getOrThrow().toString();
      itemOutputs.set(
          itemOutputs.size() - 1,
          new RecipeAdapterCustomization.ItemOutput(
              previous.encodedStack(),
              previous.amount(),
              previous.chance(),
              previous.selector(),
              Optional.of(encoded),
              previous.parallelUnaffected()));
    } catch (RuntimeException invalid) {
      throw owner.reject("Invalid adapter preview stack: " + invalid.getMessage());
    }

    return this;
  }

  /**
   * Preview custom data affects only the last appended item's artwork and hover
   * text.
   */
  public KubeJSRecipeAdapterBuilder setPreViewNBT(CompoundTag data) {
    requireMutable();
    if (last != LastRequirement.ITEM_OUTPUT || data == null) {
      throw owner.reject("Adapter preview NBT requires a last appended item output and compound data");
    }
    try {
      var previous = itemOutputs.getLast();
      var ops = registries().createSerializationContext(JsonOps.INSTANCE);
      var stack = ItemStack.SINGLE_ITEM_CODEC.parse(ops, JsonParser.parseString(previous.encodedStack())).getOrThrow();
      stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data.copy()));
      String encoded = ItemStack.SINGLE_ITEM_CODEC.encodeStart(ops, stack).getOrThrow().toString();
      itemOutputs.set(
          itemOutputs.size() - 1,
          new RecipeAdapterCustomization.ItemOutput(
              previous.encodedStack(),
              previous.amount(),
              previous.chance(),
              previous.selector(),
              Optional.of(encoded),
              previous.parallelUnaffected()));
    } catch (RuntimeException invalid) {
      throw owner.reject("Invalid adapter preview NBT: " + invalid.getMessage());
    }

    return this;
  }

  public KubeJSRecipeAdapterBuilder addInput(FluidStack fluid) {
    return addFluidInput(fluid);
  }

  public KubeJSRecipeAdapterBuilder addInputs(List<?> resources) {
    requireMutable();
    requireList(resources, "resource inputs");
    for (var resource : resources) {
      if (resource instanceof FluidStack fluid) {
        addFluidInput(fluid);
      } else if (resource instanceof ItemStack item) {
        addItemInput(item);
      } else {
        throw owner.reject("Adapter resource inputs require Item.of(...) or Fluid.of(...) values");
      }
    }
    last = LastRequirement.NONE;

    return this;
  }

  public KubeJSRecipeAdapterBuilder addOutput(ItemStack item) {
    return addItemOutput(item);
  }

  public KubeJSRecipeAdapterBuilder addOutput(FluidStack fluid) {
    return addFluidOutput(fluid);
  }

  public KubeJSRecipeAdapterBuilder addOutputs(List<?> resources) {
    requireMutable();
    requireList(resources, "resource outputs");
    for (var resource : resources) {
      if (resource instanceof ItemStack item) {
        addItemOutput(item);
      } else if (resource instanceof FluidStack fluid) {
        addFluidOutput(fluid);
      } else {
        throw owner.reject("Mixed adapter outputs require Item.of(...) or Fluid.of(...) values");
      }
    }
    last = LastRequirement.NONE;

    return this;
  }

  public KubeJSRecipeAdapterBuilder setNBTChecker(String checker) {
    return setItemInputChecker(0, checker);
  }

  public KubeJSRecipeAdapterBuilder setNBTChecker(RecipeItemCallbacks.Checker checker) {
    return setItemInputChecker(0, checker);
  }

  public KubeJSRecipeAdapterBuilder setItemInputChecker(Number row, String checker) {
    var binding = callbackRow(RecipeModifier.Io.INPUT, row, false);
    binding.checker = Optional.of(owner.identifier(checker, "item checker"));
    binding.inlineChecker = null;

    return this;
  }

  public KubeJSRecipeAdapterBuilder setItemInputChecker(Number row, RecipeItemCallbacks.Checker checker) {
    var binding = callbackRow(RecipeModifier.Io.INPUT, row, false);
    if (checker == null) {
      throw owner.reject("Adapter item checker must not be null");
    }
    binding.checker = Optional.empty();
    binding.inlineChecker = checker;

    return this;
  }

  public KubeJSRecipeAdapterBuilder addItemModifier(String modifier) {
    return addOutputModifier(lastAddedOutput(), modifier);
  }

  public KubeJSRecipeAdapterBuilder addItemModifier(RecipeItemCallbacks.Modifier modifier) {
    return addOutputModifier(lastAddedOutput(), modifier);
  }

  public KubeJSRecipeAdapterBuilder addItemOutputModifier(Number sourceRow, String modifier) {
    return addOutputModifier(callbackRow(RecipeModifier.Io.OUTPUT, sourceRow, false), modifier);
  }

  public KubeJSRecipeAdapterBuilder addItemOutputModifier(Number sourceRow, RecipeItemCallbacks.Modifier modifier) {
    return addOutputModifier(callbackRow(RecipeModifier.Io.OUTPUT, sourceRow, false), modifier);
  }

  public KubeJSRecipeAdapterBuilder addItemInputModifier(Number sourceRow, String modifier) {
    var binding = callbackRow(RecipeModifier.Io.INPUT, sourceRow, false);
    addModifierEntry(binding, new ModifierEntry(owner.identifier(modifier, "input item modifier"), null, null));

    return this;
  }

  public KubeJSRecipeAdapterBuilder addItemInputModifier(Number sourceRow, RecipeItemCallbacks.InputModifier modifier) {
    var binding = callbackRow(RecipeModifier.Io.INPUT, sourceRow, false);
    if (modifier == null) {
      throw owner.reject("Adapter input item modifier must not be null");
    }
    addModifierEntry(binding, new ModifierEntry(null, null, modifier));

    return this;
  }

  public KubeJSRecipeAdapterBuilder addInputItemModifier(String modifier) {
    return addItemInputModifier(0, modifier);
  }

  public KubeJSRecipeAdapterBuilder addInputItemModifier(RecipeItemCallbacks.InputModifier modifier) {
    return addItemInputModifier(0, modifier);
  }

  public KubeJSRecipeAdapterBuilder addInputItemModifier(Number sourceRow, String modifier) {
    return addItemInputModifier(sourceRow, modifier);
  }

  public KubeJSRecipeAdapterBuilder addInputItemModifier(Number sourceRow, RecipeItemCallbacks.InputModifier modifier) {
    return addItemInputModifier(sourceRow, modifier);
  }

  private KubeJSRecipeAdapterBuilder addOutputModifier(CallbackRow binding, String modifier) {
    addModifierEntry(binding, new ModifierEntry(owner.identifier(modifier, "output item modifier"), null, null));

    return this;
  }

  private KubeJSRecipeAdapterBuilder addOutputModifier(CallbackRow binding, RecipeItemCallbacks.Modifier modifier) {
    if (modifier == null) {
      throw owner.reject("Adapter output item modifier must not be null");
    }
    addModifierEntry(binding, new ModifierEntry(null, modifier, null));

    return this;
  }

  private void addModifierEntry(CallbackRow binding, ModifierEntry entry) {
    if (binding.modifiers.size() >= RecipeScriptOptions.MAX_ROW_MODIFIERS) {
      throw owner.reject("Too many adapter modifiers in one item row");
    }
    binding.modifiers.add(entry);
  }

  private CallbackRow lastAddedOutput() {
    requireMutable();
    if (last != LastRequirement.ITEM_OUTPUT) {
      throw owner.reject("Adapter item modifier requires a last appended item output; select source rows explicitly");
    }

    return callbackRow(RecipeModifier.Io.OUTPUT, itemOutputs.size() - 1, true);
  }

  private CallbackRow callbackRow(RecipeModifier.Io io, Number row, boolean addedOutput) {
    requireMutable();
    int index = (int) owner.integer(row, "item callback row", 0, 255);
    return callbacks.row(owner, io, index, addedOutput);
  }

  private long chemicalAmount(Number amount) {
    if (ExactIntegers.unsafeFloatingInteger(amount)) {
      throw owner.reject("Large adapter chemical amounts require an exact decimal string");
    }

    return owner.integer(amount, "chemical amount", 1, Long.MAX_VALUE);
  }

  private long decimalInteger(String amount, String field, long minimum) {
    requireMutable();
    try {
      return owner.integer(new BigDecimal(amount), field, minimum, Long.MAX_VALUE);
    } catch (RuntimeException invalid) {
      throw owner.reject("Adapter " + field + " requires an exact decimal integer");
    }
  }

  private RecipeAdapterCustomization.FluidResource captureFluid(FluidStack fluid) {
    if (fluid == null || fluid.isEmpty()) {
      throw owner.reject("Recipe adapter fluid requirement must not be empty");
    }
    try {
      var ops = registries().createSerializationContext(JsonOps.INSTANCE);
      var encoded = FluidStack.CODEC.encodeStart(ops, fluid).getOrThrow();
      var captured = FluidStack.CODEC.parse(ops, encoded.deepCopy()).getOrThrow();
      if (!FluidStack.matches(fluid, captured)) {
        throw owner.reject("Lossy fluid components cannot be used in an adapter");
      }

      return new RecipeAdapterCustomization.FluidResource(encoded.toString());
    } catch (RuntimeException invalid) {
      throw owner.reject("Invalid recipe adapter fluid: " + invalid.getMessage());
    }
  }

  private HolderLookup.Provider registries() {
    return owner.registries();
  }

  private <T> void add(List<T> rows, T row, String field) {
    if (row == null || rows.size() >= RecipeAdapterCustomization.MAX_REQUIREMENTS) {
      throw owner.reject("Missing or excessive adapter " + field + " requirements");
    }
    rows.add(row);
  }

  private void requireList(List<?> rows, String field) {
    if (rows == null) {
      throw owner.reject("Adapter " + field + " list must not be null");
    }
  }

  /** Extension: overrides native duration; CRT supplied duration modifiers. */
  public KubeJSRecipeAdapterBuilder setDuration(Number ticks) {
    requireMutable();
    duration = (int) owner.integer(ticks, "duration", 1, Integer.MAX_VALUE);

    return this;
  }

  /** Extension: native priority remains an explicit import setting. */
  public KubeJSRecipeAdapterBuilder setPriority(Number priority) {
    requireMutable();
    this.priority = (int) owner.integer(priority, "priority", Integer.MIN_VALUE, Integer.MAX_VALUE);
    prioritySpecified = true;

    return this;
  }

  /** Extension: limits this declaration to the selected source recipe IDs. */
  public KubeJSRecipeAdapterBuilder setSourceRecipes(List<String> sourceIds) {
    requireMutable();
    if (sourceIds == null) {
      throw owner.reject("Recipe adapter source ID list must not be null");
    }
    var selected = new ArrayList<ResourceLocation>(sourceIds.size());
    var seen = new HashSet<ResourceLocation>();
    for (String source : sourceIds) {
      var parsed = owner.identifier(source, "source recipe");
      if (!seen.add(parsed)) {
        throw owner.reject("Duplicate recipe adapter source " + source);
      }
      selected.add(parsed);
    }
    recipes = List.copyOf(selected);
    allRecipes = false;

    return this;
  }

  /** Extension: distinguishes multiple imports from the same adapter. */
  public KubeJSRecipeAdapterBuilder setImportId(String id) {
    requireMutable();
    if (id == null || !id.matches("[a-zA-Z0-9_.-]{1,64}")) {
      throw owner.reject("Recipe adapter import ID must use 1..64 letters, digits, underscores, dots or hyphens");
    }
    this.id = id;

    return this;
  }

  public void build() {
    requireMutable();
    try {
      int[] retainedOutputRows = prepareTriggeredResources();
      var compiledCallbacks = callbacks.compile(machine, adapter, id, retainedOutputRows);
      var customization = new RecipeAdapterCustomization(
          modifiers,
          interfaceInputs,
          fluidInputs,
          fluidOutputs,
          Optional.empty(),
          chemicalInputs,
          chemicalOutputs,
          chemicalPerTick,
          itemOutputs,
          selectors,
          parallelized,
          failureAction,
          energyOutputPerTick,
          threadName,
          maxThreads,
          tooltips,
          compiledCallbacks.bindings(),
          itemParallelism,
          chemicalPerTickOutputs,
          fluidPerTickInputs,
          fluidPerTickOutputs,
          new RecipeAdapterCustomization.ResourceAdditions(resourceAdditions().toString(), captureAdapterResources()));
      owner.register(
          machine,
          new RecipeAdapterImport(
              id,
              adapter,
              recipes,
              allRecipes,
              duration,
              energyPerTick,
              priority,
              customization,
              prioritySpecified));
      compiledCallbacks.checkers().forEach(RecipeScriptCallbacks::adapterChecker);
      compiledCallbacks.modifiers().forEach(RecipeScriptCallbacks::adapterModifier);
      compiledCallbacks.inputModifiers().forEach(RecipeScriptCallbacks::adapterInputModifier);
      arrayCallbacks.forEach(KubeJSIngredientArrayBuilder.GroupCallbacks::captureAdapterCallbacks);
      built = true;
    } catch (RuntimeException invalid) {
      throw owner.reject("Invalid adapter declaration: " + invalid.getMessage());
    }
  }

  private JsonObject resourceAdditions() {
    var result = new JsonObject();
    var inputs = new JsonArray();
    itemInputs.forEach(row -> inputs.add(row.deepCopy()));
    if (!inputs.isEmpty()) {
      result.add("inputs", inputs);
    }
    var ops = owner.registries().createSerializationContext(JsonOps.INSTANCE);
    if (!ingredientArrays.isEmpty()) {
      result.add(
          "ingredient_arrays",
          IngredientArrayData.Group.CODEC.listOf().encodeStart(ops, ingredientArrays).getOrThrow());
    }
    if (!randomItemOutputs.isEmpty()) {
      result.add(
          "random_item_outputs",
          IngredientArrayData.Group.CODEC.listOf().encodeStart(ops, randomItemOutputs).getOrThrow());
    }
    if (!outputRanges.isEmpty()) {
      var ranges = new JsonArray();
      outputRanges.forEach((index, range) -> {
        var row = new JsonObject();
        row.addProperty("row", index);
        row.addProperty("min", range[0]);
        row.addProperty("max", range[1]);
        ranges.add(row);
      });
      result.add("output_ranges", ranges);
    }
    loadJei.ifPresent(value -> result.addProperty("load_jei", value));
    addItemPayments(result);
    addTriggeredResources(result);

    return result;
  }
}
