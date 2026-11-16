package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.machine.recipe.RecipeScriptCallbacks;
import com.lw.modularmachine.util.math.ExactIntegers;
import com.lw.modularmachine.integration.kubejs.KubeJSAdapterCallbackBindings.CallbackRow;
import com.lw.modularmachine.integration.kubejs.KubeJSAdapterCallbackBindings.ModifierEntry;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterImport;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterCustomization;
import com.lw.modularmachine.api.recipe.ChemicalRequirement;
import com.lw.modularmachine.api.recipe.RecipeFailureAction;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.recipe.RecipeExecutionOptions;
import com.lw.modularmachine.api.recipe.RecipeScriptOptions;
import com.lw.modularmachine.api.recipe.RecipeItemCallbacks;
import com.lw.modularmachine.api.smart.SmartNumberRequirement;
import com.lw.modularmachine.machine.recipe.ItemStackSnapshots;
import com.lw.modularmachine.machine.recipe.ResourceSelector;
import com.mojang.serialization.JsonOps;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.EnumMap;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.fluids.FluidStack;
import com.google.gson.JsonParser;

/**
 * One CRT-style adapter import; its registration owns generation validity and
 * closing.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class KubeJSRecipeAdapterBuilder {
  private enum LastRequirement {
    NONE,
    ITEM_OUTPUT,
    FLUID_INPUT,
    FLUID_OUTPUT,
    FLUID_PER_TICK,
    CHEMICAL,
    ENERGY,
    ENERGY_OUTPUT
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
  private final List<RecipeModifier> modifiers = new ArrayList<>();
  private final List<SmartNumberRequirement> interfaceInputs = new ArrayList<>();
  private final List<RecipeAdapterCustomization.FluidResource> fluidInputs = new ArrayList<>(),
      fluidOutputs = new ArrayList<>();
  private Optional<RecipeAdapterCustomization.FluidResource> fluidPerTick = Optional.empty();
  private final List<ChemicalRequirement> chemicalInputs = new ArrayList<>(), chemicalOutputs = new ArrayList<>(),
      chemicalPerTick = new ArrayList<>();
  private final List<RecipeAdapterCustomization.ItemOutput> itemOutputs = new ArrayList<>();
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

  KubeJSRecipeAdapterBuilder(KubeJSRecipeAdapterRegistration owner, ResourceLocation machine, ResourceLocation adapter, String id) {
    this.owner = owner;
    this.machine = machine;
    this.adapter = adapter;
    this.id = id;
  }

  private void requireMutable() {
    owner.requireOpen();
    if (built) {
      throw owner.reject("Recipe adapter builder has already been built");
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
    if (fluidPerTick.isPresent()) {
      throw owner.reject("Only one adapter fluid-per-tick addition is supported");
    }
    fluidPerTick = Optional.of(captureFluid(fluid));
    last = LastRequirement.FLUID_PER_TICK;

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
    if (last == LastRequirement.ITEM_OUTPUT) {
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
    } else if (last == LastRequirement.FLUID_INPUT || last == LastRequirement.FLUID_OUTPUT) {
      var rows = last == LastRequirement.FLUID_INPUT ? fluidInputs : fluidOutputs;
      var encoded = JsonParser.parseString(rows.getLast().encoded()).getAsJsonObject();
      encoded.addProperty("chance", chance.doubleValue());
      rows.set(rows.size() - 1, new RecipeAdapterCustomization.FluidResource(encoded.toString()));
    } else {
      throw owner.reject("Adapter chance requires the last single item output or startup/output fluid row");
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
        lastChemicals
            .set(lastChemicals.size() - 1, new ChemicalRequirement(previous.chemical(), previous.amount(), selector));
      }
      case FLUID_INPUT -> cohort(RecipeAdapterCustomization.Phase.FLUID_INPUT, selector);
      case FLUID_OUTPUT -> cohort(RecipeAdapterCustomization.Phase.FLUID_OUTPUT, selector);
      case FLUID_PER_TICK -> cohort(RecipeAdapterCustomization.Phase.FLUID_PER_TICK, selector);
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
    if (last == LastRequirement.ITEM_OUTPUT) {
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
      var rows = last == LastRequirement.FLUID_INPUT ? fluidInputs : fluidOutputs;
      var encoded = JsonParser.parseString(rows.getLast().encoded()).getAsJsonObject();
      encoded.addProperty("parallel_unaffected", enabled);
      rows.set(rows.size() - 1, new RecipeAdapterCustomization.FluidResource(encoded.toString()));
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
      if (!(resource instanceof FluidStack fluid)) {
        throw owner.reject(
            "Adapter resource inputs currently require Fluid.of(...) values; additional item inputs need mixed alias planning");
      }
      addFluidInput(fluid);
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
      var compiledCallbacks = callbacks.compile(machine, adapter, id);
      var customization = new RecipeAdapterCustomization(
          modifiers,
          interfaceInputs,
          fluidInputs,
          fluidOutputs,
          fluidPerTick,
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
          itemParallelism);
      owner.register(
          machine,
          new RecipeAdapterImport(id, adapter, recipes, allRecipes, duration, energyPerTick, priority, customization));
      compiledCallbacks.checkers().forEach(RecipeScriptCallbacks::adapterChecker);
      compiledCallbacks.modifiers().forEach(RecipeScriptCallbacks::adapterModifier);
      compiledCallbacks.inputModifiers().forEach(RecipeScriptCallbacks::adapterInputModifier);
      built = true;
    } catch (RuntimeException invalid) {
      throw owner.reject("Invalid adapter declaration: " + invalid.getMessage());
    }
  }
}
