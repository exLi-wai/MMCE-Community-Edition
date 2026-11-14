package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.recipe.search.RecipeCandidate;
import com.lw.modularmachine.api.recipe.RecipeSelectors;
import com.lw.modularmachine.api.recipe.RecipeFailureAction;
import com.lw.modularmachine.api.recipe.ChemicalRecipeData;
import com.lw.modularmachine.api.recipe.ChemicalRequirement;
import com.lw.modularmachine.api.recipe.RecipeExecutionOptions;
import com.lw.modularmachine.api.recipe.RecipeItemCallbacks;
import com.google.gson.JsonObject;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeSnapshot;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Immutable paid-run state. Payload is shared privately between transitions; no
 * live recipe/world reference.
 */
public final class FrozenItemRun {
  public enum State {
    RUNNING,
    OUTPUT_PENDING
  }

  private static final Set<String> FIELDS = Set.of(
      "schema",
      "run_id",
      "machine",
      "recipe",
      "duration",
      "progress",
      "state",
      "inputs_committed",
      "definition_epoch",
      "definition",
      "ports",
      "pattern_inputs",
      "dynamic_lengths",
      "input_group",
      "parallel_instances",
      "parallel_base",
      "outputs",
      "fluid_outputs",
      "last_progress_tick",
      "random_seed",
      "item_callback_epoch",
      "energy_output_progress");
  private final CompoundTag data;
  private final UUID runId;
  private final ResourceLocation machineId, recipeId;
  private final int schema, duration, progress;
  private final long energyPerTick;
  private final long energyOutputPerTick;
  private final int energyOutputProgress;
  private final RecipeExecutionOptions executionOptions;
  private final long itemCallbackEpoch;
  private final RecipeFailureAction failureAction;
  private final long randomSeed;
  private final CompoundTag fluidPerTickData;
  private final FluidStack fluidPerTick;
  private final State state;
  private final long lastProgressTick;
  private final Map<String, PortKind> logicalPorts;
  private final Map<String, String> logicalSelectors;
  private final RecipeSelectors selectors;
  private final ChemicalRecipeData chemicals;
  private final int inputGroup;
  private final Map<String, Integer> logicalInputGroups;
  private final int parallelInstances;
  private final int parallelBase;
  private final Map<String, Integer> dynamicLengths;
  private final PatternRunSources.Binding patternSource;
  private final Set<PortKind> patternInputs;

  private FrozenItemRun(CompoundTag source, HolderLookup.Provider registries) {
    for (String key : source.getAllKeys()) {
      if (!FIELDS.contains(key)) {
        throw new IllegalArgumentException("Unknown run field: " + key);
      }
    }
    if (!source.contains("schema", Tag.TAG_INT)
        || source.getInt("schema") != 3
        || !source.hasUUID("run_id")
        || !source.contains("machine", Tag.TAG_STRING)
        || !source.contains("recipe", Tag.TAG_STRING)
        || !source.contains("duration", Tag.TAG_INT)
        || !source.contains("progress", Tag.TAG_INT)
        || !source.contains("state", Tag.TAG_STRING)
        || !source.contains("inputs_committed", Tag.TAG_BYTE)
        || source.getByte("inputs_committed") != 1
        || !source.contains("definition_epoch", Tag.TAG_LONG)
        || source.getLong("definition_epoch") < 0
        || !source.contains("definition", Tag.TAG_COMPOUND)
        || !source.contains("ports", Tag.TAG_LIST)) {
      throw new IllegalArgumentException("Invalid or future run header");
    }
    schema = source.getInt("schema");
    if (source.contains("item_callback_epoch")
        && (!source.contains("item_callback_epoch", Tag.TAG_LONG) || source.getLong("item_callback_epoch") < 0)) {
      throw new IllegalArgumentException("Invalid prepared item callback epoch");
    }
    itemCallbackEpoch = source.contains("item_callback_epoch") ? source.getLong("item_callback_epoch") : -1;
    if (source.contains("dynamic_lengths") && !source.contains("dynamic_lengths", Tag.TAG_COMPOUND)) {
      throw new IllegalArgumentException("Invalid frozen dynamic lengths");
    }
    var frozenLengths = source.getCompound("dynamic_lengths");
    var lengths = new LinkedHashMap<String, Integer>();
    if (frozenLengths.size() > 64) {
      throw new IllegalArgumentException("Too many frozen dynamic segments");
    }
    for (String id : frozenLengths.getAllKeys()) {
      if (id.isBlank()
          || id.length() > 128
          || !frozenLengths.contains(id, Tag.TAG_INT)
          || frozenLengths.getInt(id) < 0) {
        throw new IllegalArgumentException("Invalid frozen dynamic segment length");
      }
      lengths.put(id, frozenLengths.getInt(id));
    }
    dynamicLengths = Map.copyOf(lengths);
    if (source.contains("input_group") && !source.contains("input_group", Tag.TAG_INT)) {
      throw new IllegalArgumentException("Invalid frozen input group");
    }
    inputGroup = InputGroup.require(source.contains("input_group") ? source.getInt("input_group") : InputGroup.SHARED);
    parallelInstances = source.contains("parallel_instances") ? source.getInt("parallel_instances") : 1;
    if (parallelInstances < 1
        || source.contains("parallel_instances") && !source.contains("parallel_instances", Tag.TAG_INT)) {
      throw new IllegalArgumentException("Invalid parallel instances");
    }
    parallelBase = source.contains("parallel_base") ? source.getInt("parallel_base") : 1;
    if (parallelBase < 1 || source.contains("parallel_base") && !source.contains("parallel_base", Tag.TAG_INT)) {
      throw new IllegalArgumentException("Invalid frozen parallel baseline");
    }
    runId = source.getUUID("run_id");
    machineId = ResourceLocation.parse(source.getString("machine"));
    recipeId = ResourceLocation.parse(source.getString("recipe"));
    duration = source.getInt("duration");
    progress = source.getInt("progress");
    if (duration <= 0 || progress < 0 || progress > duration) {
      throw new IllegalArgumentException("Invalid run progress");
    }
    state = State.valueOf(source.getString("state"));
    if (!source.contains("last_progress_tick", Tag.TAG_LONG)) {
      throw new IllegalArgumentException("Missing run tick stamp");
    }
    lastProgressTick = source.getLong("last_progress_tick");
    if (!source.contains("random_seed", Tag.TAG_LONG) || source.getLong("random_seed") < 0) {
      throw new IllegalArgumentException("Missing or invalid run random seed");
    }
    var patternKinds = EnumSet.noneOf(PortKind.class);
    if (source.contains("pattern_inputs")) {
      if (!source.contains("pattern_inputs", Tag.TAG_COMPOUND)) {
        throw new IllegalArgumentException("Invalid frozen pattern inputs");
      }
      var pattern = source.getCompound("pattern_inputs");
      patternSource = PatternRunSources.Binding.load(pattern);
      if (!(pattern.get("kinds") instanceof ListTag kinds)
          || kinds.isEmpty()
          || kinds.getElementType() != Tag.TAG_STRING
          || kinds.size() > 3) {
        throw new IllegalArgumentException("Invalid frozen pattern input roles");
      }
      for (var value : kinds) {
        var kind = PortKind.valueOf(value.getAsString());
        if (kind != PortKind.ITEM_INPUT && kind != PortKind.FLUID_INPUT && kind != PortKind.CHEMICAL_INPUT
            || !patternKinds.add(kind)) {
          throw new IllegalArgumentException("Invalid or duplicate frozen pattern input role");
        }
      }
    } else {
      patternSource = null;
    }
    patternInputs = Set.copyOf(patternKinds);
    var ports = source.getList("ports", Tag.TAG_COMPOUND);
    var mapped = new LinkedHashMap<String, PortKind>();
    var mappedSelectors = new LinkedHashMap<String, String>();
    var mappedGroups = new LinkedHashMap<String, Integer>();
    if (ports.isEmpty() && patternInputs.isEmpty() || ports.size() > 4096) {
      throw new IllegalArgumentException("Invalid run port map");
    }
    for (int i = 0; i < ports.size(); i++) {
      var port = ports.getCompound(i);
      String id = port.getString("id");
      if (!Set.of("id", "kind", "selector", "input_group").containsAll(port.getAllKeys())
          || !port.contains("id", Tag.TAG_STRING)
          || !port.contains("kind", Tag.TAG_STRING)
          || port.contains("selector") && !port.contains("selector", Tag.TAG_STRING)
          || port.contains("input_group") && !port.contains("input_group", Tag.TAG_INT)
          || id.isBlank()
          || id.length() > 128
          || mapped.put(id, PortKind.valueOf(port.getString("kind"))) != null) {
        throw new IllegalArgumentException("Invalid or duplicate run port identity");
      }
      mappedSelectors.put(id, ResourceSelector.require(port.getString("selector")));
      int group = InputGroup.require(port.contains("input_group") ? port.getInt("input_group") : InputGroup.SHARED);
      var kind = mapped.get(id);
      if (group != InputGroup.SHARED
          && kind != PortKind.ITEM_INPUT
          && kind != PortKind.FLUID_INPUT
          && kind != PortKind.CHEMICAL_INPUT) {
        throw new IllegalArgumentException("Non-groupable frozen port");
      }
      mappedGroups.put(id, group);
    }
    logicalPorts = Map.copyOf(mapped);
    logicalSelectors = Map.copyOf(mappedSelectors);
    logicalInputGroups = Map.copyOf(mappedGroups);
    if (inputGroup > 0 && !mappedGroups.containsValue(inputGroup)) {
      throw new IllegalArgumentException("Frozen input group has no mapped input");
    }
    var definition = source.getCompound("definition");
    if (!definition.contains("failure_action", Tag.TAG_STRING)
        || !definition.contains("parallelized", Tag.TAG_BYTE)
        || definition.getByte("parallelized") < 0
        || definition.getByte("parallelized") > 1) {
      throw new IllegalArgumentException("Frozen recipe is missing resolved defaults");
    }
    if (definition.contains("modifiers") || definition.contains("item_callbacks")) {
      throw new IllegalArgumentException("Frozen recipe contains unresolved modifiers");
    }
    var declaration = NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, definition).getAsJsonObject();
    MachineRecipeJson.restoreRowBooleans(declaration, definition);
    declaration.addProperty("parallelized", definition.getBoolean("parallelized"));
    var header = MachineRecipeJson.validate(declaration);
    selectors = header.selectors();
    chemicals = declaration.has("chemicals") ? ChemicalRecipeData.CODEC
        .parse(JsonOps.INSTANCE, declaration.get("chemicals")).getOrThrow() : ChemicalRecipeData.NONE;
    validateResourceBindings(declaration);
    if (!definition.getString("type").equals(MachineRecipeJson.TYPE)
        || !header.machine().equals(machineId.toString())
        || header.duration() != duration) {
      throw new IllegalArgumentException("Run/definition mismatch");
    }
    energyPerTick = header.energyPerTick();
    energyOutputPerTick = header.energyOutputPerTick();
    if (energyOutputPerTick > 0 && !source.contains("energy_output_progress", Tag.TAG_INT)
        || source.contains("energy_output_progress") && !source.contains("energy_output_progress", Tag.TAG_INT)) {
      throw new IllegalArgumentException("Missing or invalid energy output progress");
    }
    energyOutputProgress = source.getInt("energy_output_progress");
    if (energyOutputProgress < 0
        || energyOutputProgress > duration
        || energyOutputPerTick > 0 && energyOutputProgress < progress
        || energyOutputPerTick == 0 && energyOutputProgress != 0) {
      throw new IllegalArgumentException("Invalid energy output progress");
    }
    executionOptions = RecipeExecutionOptions.CODEC.parse(JsonOps.INSTANCE, declaration).getOrThrow();
    failureAction = RecipeFailureAction.parse(header.failureAction());
    randomSeed = source.getLong("random_seed");
    if (header.hasFluidPerTick()) {
      fluidPerTickData = definition.getCompound("fluid_per_tick").copy();
      fluidPerTick = registries == null ? null : decodeFluidPerTick(fluidPerTickData, registries);
    } else {
      fluidPerTickData = new CompoundTag();
      fluidPerTick = null;
    }
    if (state == State.OUTPUT_PENDING) {
      if (progress != duration
          || !source.contains("outputs", Tag.TAG_LIST)
          || !sampledOutputs(definition.getList("outputs", Tag.TAG_COMPOUND), randomSeed, parallelInstances)
              .equals(source.getList("outputs", Tag.TAG_COMPOUND))
          || definition.contains("fluid_outputs")
              && (!source.contains("fluid_outputs", Tag.TAG_LIST)
                  || !sampledFluids(
                      definition.getList("fluid_outputs", Tag.TAG_COMPOUND),
                      randomSeed,
                      parallelInstances,
                      false).equals(source.getList("fluid_outputs", Tag.TAG_COMPOUND)))
          || !definition.contains("fluid_outputs") && source.contains("fluid_outputs")) {
        throw new IllegalArgumentException("Pending output is not the frozen recipe result");
      }
    } else {
      if (source.contains("outputs") || source.contains("fluid_outputs")) {
        throw new IllegalArgumentException("Running recipe cannot own generated output");
      }
    }
    data = source.copy();
  }

  private FrozenItemRun(FrozenItemRun before, int progress, State state, long tick) {
    data = before.data;
    runId = before.runId;
    machineId = before.machineId;
    recipeId = before.recipeId;
    duration = before.duration;
    energyPerTick = before.energyPerTick;
    energyOutputPerTick = before.energyOutputPerTick;
    energyOutputProgress = energyOutputPerTick > 0 ? Math.max(before.energyOutputProgress, progress) : 0;
    executionOptions = before.executionOptions;
    itemCallbackEpoch = before.itemCallbackEpoch;
    failureAction = before.failureAction;
    randomSeed = before.randomSeed;
    fluidPerTickData = before.fluidPerTickData;
    fluidPerTick = before.fluidPerTick;
    logicalPorts = before.logicalPorts;
    logicalSelectors = before.logicalSelectors;
    selectors = before.selectors;
    chemicals = before.chemicals;
    inputGroup = before.inputGroup;
    logicalInputGroups = before.logicalInputGroups;
    parallelInstances = before.parallelInstances;
    parallelBase = before.parallelBase;
    dynamicLengths = before.dynamicLengths;
    patternSource = before.patternSource;
    patternInputs = before.patternInputs;
    schema = 3;
    this.progress = progress;
    this.state = state;
    lastProgressTick = tick;
  }

  public static FrozenItemRun read(CompoundTag data) {
    return new FrozenItemRun(data, null);
  }

  public static FrozenItemRun read(CompoundTag data, HolderLookup.Provider registries) {
    return new FrozenItemRun(data, registries);
  }

  static FrozenItemRun freeze(ResourceLocation recipeId, MachineRecipe recipe, long epoch, HolderLookup.Provider registries, List<RecipeExecutionTicket.PortSnapshot> ports, long tick) {
    return freeze(recipeId, recipe, epoch, registries, ports, tick, FactoryUpgradeSnapshot.identity(0));
  }

  static FrozenItemRun freeze(ResourceLocation recipeId, MachineRecipe recipe, long epoch, HolderLookup.Provider registries, List<RecipeExecutionTicket.PortSnapshot> ports, long tick, FactoryUpgradeSnapshot upgrades) {
    return freeze(recipeId, recipe, epoch, registries, ports, tick, upgrades, InputGroup.SHARED);
  }

  static FrozenItemRun freeze(ResourceLocation recipeId, MachineRecipe recipe, long epoch, HolderLookup.Provider registries, List<RecipeExecutionTicket.PortSnapshot> ports, long tick, FactoryUpgradeSnapshot upgrades, int inputGroup) {
    return freeze(recipeId, recipe, epoch, registries, ports, tick, upgrades, inputGroup, 1);
  }

  static FrozenItemRun freeze(ResourceLocation recipeId, MachineRecipe recipe, long epoch, HolderLookup.Provider registries, List<RecipeExecutionTicket.PortSnapshot> ports, long tick, FactoryUpgradeSnapshot upgrades, int inputGroup, int parallelInstances) {
    return freeze(recipeId, recipe, epoch, registries, ports, tick, upgrades, inputGroup, parallelInstances, Map.of());
  }

  static FrozenItemRun freeze(ResourceLocation recipeId, MachineRecipe recipe, long epoch, HolderLookup.Provider registries, List<RecipeExecutionTicket.PortSnapshot> ports, long tick, FactoryUpgradeSnapshot upgrades, int inputGroup, int parallelInstances, Map<String, Integer> dynamicLengths) {
    return freeze(
        recipeId,
        recipe,
        epoch,
        registries,
        ports,
        tick,
        upgrades,
        inputGroup,
        parallelInstances,
        dynamicLengths,
        null);
  }

  static FrozenItemRun freeze(ResourceLocation recipeId, MachineRecipe recipe, long epoch, HolderLookup.Provider registries, List<RecipeExecutionTicket.PortSnapshot> ports, long tick, FactoryUpgradeSnapshot upgrades, int inputGroup, int parallelInstances, Map<String, Integer> dynamicLengths, RecipeItemCallbacks.Context callbackContext) {
    var encodedDefinition = Recipe.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), recipe)
        .getOrThrow();
    if (!(encodedDefinition instanceof CompoundTag)) {
      throw new IllegalArgumentException("Machine recipe did not encode as a compound");
    }
    var definition = applyFactoryUpgrades((CompoundTag) encodedDefinition, upgrades);
    scaleDefinition(definition, parallelInstances);
    long callbackEpoch = -1;
    if (!recipe.scriptOptions().itemCallbacks().isEmpty()) {
      if (callbackContext == null) {
        throw new IllegalArgumentException("Item callback recipes require a prepared controller context");
      }
      var declaration = NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, definition).getAsJsonObject();
      MachineRecipeJson.restoreRowBooleans(declaration, definition);
      declaration.addProperty("parallelized", definition.getBoolean("parallelized"));
      var prepared = RecipeItemCallbackService
          .prepareOutputs(declaration, recipe.scriptOptions(), callbackContext, registries)
          .orElseThrow(() -> new IllegalStateException("Item callback preparation rejected the recipe"));
      var parsed = MachineRecipe.CODEC.codec()
          .parse(registries.createSerializationContext(JsonOps.INSTANCE), prepared.declaration()).getOrThrow();
      definition = (CompoundTag) Recipe.CODEC
          .encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), parsed).getOrThrow();
      callbackEpoch = prepared.epoch();
    }
    int duration = definition.getInt("duration");
    var tag = new CompoundTag();
    UUID runId = UUID.randomUUID();
    tag.putInt("schema", 3);
    tag.putInt("input_group", InputGroup.require(inputGroup));
    tag.putInt("parallel_instances", parallelInstances);
    tag.putInt("parallel_base", upgrades.parallelLimit());
    tag.putUUID("run_id", runId);
    tag.putString("machine", recipe.machineId().toString());
    tag.putString("recipe", recipeId.toString());
    tag.putInt("duration", duration);
    tag.putInt("progress", 0);
    if (recipe.energyOutputPerTick() > 0) {
      tag.putInt("energy_output_progress", 0);
    }
    tag.putString("state", "RUNNING");
    tag.putLong("last_progress_tick", tick);
    tag.putBoolean("inputs_committed", true);
    tag.putLong("definition_epoch", epoch);
    if (callbackEpoch >= 0) {
      tag.putLong("item_callback_epoch", callbackEpoch);
    }
    long seed = recipe.randomSeed() == 0 ? (runId.getMostSignificantBits() ^ runId
        .getLeastSignificantBits()) & Long.MAX_VALUE : recipe.randomSeed();
    tag.putLong("random_seed", seed);
    tag.put("definition", definition);
    var mapping = new ListTag();
    var logicalBindings = new LinkedHashMap<String, CompoundTag>();
    PatternRunSources.Binding patternSource = null;
    var patternKinds = EnumSet.noneOf(PortKind.class);
    for (var port : ports) {
      if (port.patternInput()) {
        var binding = PatternRunSources.Binding.capture(port.patternSource());
        if (patternSource != null && !patternSource.equals(binding)) {
          throw new IllegalArgumentException("Mixed frozen pattern sources");
        }
        patternSource = binding;
        patternKinds.add(port.kind());
        continue;
      }
      var entry = new CompoundTag();
      entry.putString("id", port.logicalPartId());
      entry.putString("kind", port.kind().name());
      entry.putInt("input_group", port.inputGroup());
      if (!port.selector().isEmpty()) {
        entry.putString("selector", port.selector());
      }
      var previous = logicalBindings.putIfAbsent(port.logicalPartId(), entry);
      if (previous != null && !previous.equals(entry)) {
        throw new IllegalArgumentException("Conflicting physical tanks for one logical port");
      }
    }
    logicalBindings.values().forEach(mapping::add);
    tag.put("ports", mapping);
    if (patternSource != null) {
      var pattern = patternSource.save();
      var kinds = new ListTag();
      patternKinds.forEach(kind -> kinds.add(StringTag.valueOf(kind.name())));
      pattern.put("kinds", kinds);
      tag.put("pattern_inputs", pattern);
    }
    if (!dynamicLengths.isEmpty()) {
      var lengths = new CompoundTag();
      dynamicLengths.forEach(lengths::putInt);
      tag.put("dynamic_lengths", lengths);
    }

    return read(tag, registries);
  }

  static CompoundTag applyFactoryUpgrades(CompoundTag source, FactoryUpgradeSnapshot upgrades) {
    var definition = source.copy();
    int duration = upgrades.durationFor(definition.getInt("duration"));
    definition.putInt("duration", duration);
    if (definition.contains("energy_per_tick", Tag.TAG_ANY_NUMERIC)) {
      definition.putLong("energy_per_tick", upgrades.energyPerTick(definition.getLong("energy_per_tick")));
    }
    if (definition.contains("fluid_per_tick", Tag.TAG_COMPOUND)) {
      var fluidFee = definition.getCompound("fluid_per_tick");
      fluidFee.putInt("amount", Math.toIntExact(upgrades.fluidPerTick(fluidFee.getInt("amount"))));
    }

    return definition;
  }

  public UUID runId() {
    return runId;
  }

  public ResourceLocation machineId() {
    return machineId;
  }

  public ResourceLocation recipeId() {
    return recipeId;
  }

  public int duration() {
    return duration;
  }

  public long energyPerTick() {
    return energyPerTick;
  }

  public long energyOutputPerTick() {
    return energyOutputPerTick;
  }

  /** Already emitted positions remain paid through RESET/DECREASE and reload. */
  long nextEnergyOutput() {
    return state == State.RUNNING && progress < duration && progress >= energyOutputProgress ? energyOutputPerTick : 0;
  }

  public RecipeExecutionOptions executionOptions() {
    return executionOptions;
  }

  long preparedItemCallbackEpoch() {
    return itemCallbackEpoch;
  }

  boolean preparedItemCallbacksCurrent() {
    return itemCallbackEpoch < 0 || RecipeItemCallbackService.isCurrent(itemCallbackEpoch);
  }

  public RecipeFailureAction failureAction() {
    return failureAction;
  }

  public long randomSeed() {
    return randomSeed;
  }

  public FluidStack fluidPerTick(HolderLookup.Provider registries) {
    if (fluidPerTickData.isEmpty()) {
      return FluidStack.EMPTY;
    }

    return fluidPerTick == null ? decodeFluidPerTick(fluidPerTickData, registries) : fluidPerTick.copy();
  }

  public List<ChemicalRequirement> chemicalPerTick() {
    return chemicals.perTick();
  }

  public List<ChemicalRequirement> chemicalOutputs() {
    if (!pendingOutput()) {
      throw new IllegalStateException("Outputs have not been generated");
    }

    return chemicals.outputs();
  }

  private void validateResourceBindings(JsonObject declaration) {
    if (!declaration.getAsJsonArray("inputs").isEmpty()
        && !logicalPorts.containsValue(PortKind.ITEM_INPUT)
        && !patternInputs.contains(PortKind.ITEM_INPUT)
        || !declaration.getAsJsonArray("outputs").isEmpty() && !logicalPorts.containsValue(PortKind.ITEM_OUTPUT)) {
      throw new IllegalArgumentException("Item requirements lack logical item ports");
    }
    chemicals.inputs().forEach(value -> requirePort(PortKind.CHEMICAL_INPUT, value.selector()));
    chemicals.perTick().forEach(value -> requirePort(PortKind.CHEMICAL_INPUT, value.selector()));
    chemicals.outputs().forEach(value -> requirePort(PortKind.CHEMICAL_OUTPUT, value.selector()));
    if (declaration.has("fluid_inputs")) {
      requirePort(PortKind.FLUID_INPUT, selectors.fluidInput());
    }
    if (declaration.has("fluid_outputs")) {
      requirePort(PortKind.FLUID_OUTPUT, selectors.fluidOutput());
    }
    if (declaration.has("fluid_per_tick")) {
      requirePort(PortKind.FLUID_INPUT, selectors.fluidPerTick());
    }
    if (declaration.has("energy_per_tick") && declaration.get("energy_per_tick").getAsLong() > 0) {
      requirePort(PortKind.ENERGY_INPUT, selectors.energyInput());
    }
    if (declaration.has("energy_output_per_tick") && declaration.get("energy_output_per_tick").getAsLong() > 0) {
      requirePort(PortKind.ENERGY_OUTPUT, selectors.energyOutput());
    }
  }

  private void requirePort(PortKind kind, String selector) {
    boolean found = patternInputs.contains(kind) && selector.equals(patternSource.selector())
        || logicalPorts.entrySet().stream().anyMatch(
            entry -> entry.getValue() == kind
                && selector.equals(logicalSelectors.get(entry.getKey()))
                && (!kind.isInput()
                    || logicalInputGroups.get(entry.getKey()) == InputGroup.SHARED
                    || logicalInputGroups.get(entry.getKey()) == inputGroup));
    if (!found) {
      throw new IllegalArgumentException("Frozen recipe lacks a matching " + kind.path() + " port");
    }
  }

  private static FluidStack decodeFluidPerTick(CompoundTag data, HolderLookup.Provider registries) {
    return FluidStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), data).getOrThrow();
  }

  public int progress() {
    return progress;
  }

  public State state() {
    return state;
  }

  public long lastProgressTick() {
    return lastProgressTick;
  }

  public Map<String, PortKind> logicalPorts() {
    return logicalPorts;
  }

  public RecipeSelectors selectors() {
    return selectors;
  }

  public int inputGroup() {
    return inputGroup;
  }

  public int parallelInstances() {
    return parallelInstances;
  }

  public int parallelBase() {
    return parallelBase;
  }

  private static void scaleDefinition(CompoundTag definition, int instances) {
    if (instances < 1) {
      throw new IllegalArgumentException("Invalid parallel instances");
    }
    if (instances == 1) {
      return;
    }
    if (definition.contains("energy_per_tick", Tag.TAG_ANY_NUMERIC)) {
      definition.putLong("energy_per_tick", Math.multiplyExact(definition.getLong("energy_per_tick"), instances));
    }
    if (definition.contains("energy_output_per_tick", Tag.TAG_ANY_NUMERIC)) {
      definition.putLong(
          "energy_output_per_tick",
          Math.multiplyExact(definition.getLong("energy_output_per_tick"), instances));
    }
    scaleRows(definition, "inputs", instances);
    scaleRows(definition, "fluid_inputs", instances);
    scaleRows(definition, "fluid_outputs", instances);
    if (definition.contains("chemicals", Tag.TAG_COMPOUND)) {
      var chemical = definition.getCompound("chemicals");
      scaleRows(chemical, "inputs", instances);
      scaleRows(chemical, "outputs", instances);
      scaleRows(chemical, "per_tick", instances);
    }
    if (definition.contains("fluid_per_tick", Tag.TAG_COMPOUND)) {
      var fee = definition.getCompound("fluid_per_tick");
      fee.putInt("amount", Math.toIntExact(Math.multiplyExact(fee.getInt("amount"), instances)));
    }
  }

  private static void scaleRows(CompoundTag definition, String field, int instances) {
    if (!definition.contains(field, Tag.TAG_LIST)) {
      return;
    }
    var rows = definition.getList(field, Tag.TAG_COMPOUND);
    for (int index = 0; index < rows.size(); index++) {
      var row = rows.getCompound(index);
      if (row.getBoolean("parallel_unaffected")) {
        continue;
      }
      long amount = Math.multiplyExact(row.getLong("amount"), instances);
      if (field.startsWith("fluid_")) {
        row.putInt("amount", Math.toIntExact(amount));
      } else {
        row.putLong("amount", amount);
      }
    }
  }

  /** A paid run never silently switches its input routing after GUI edits. */
  boolean matchesInputGroups(RecipeExecutionTicket ticket) {
    return ticket.inputGroupsMatch(logicalInputGroups);
  }

  public boolean matchesBindings(MachineDefinition definition) {
    var roles = new LinkedHashMap<String, PortKind>();
    var labels = new LinkedHashMap<String, String>();
    for (var part : definition.parts()) {
      if (part.rule().port() != null) {
        roles.put(part.id(), part.rule().port());
        labels.put(part.id(), part.selector());
      }
    }

    return machineId.equals(definition.id())
        && dynamicLengths.equals(definition.dynamicLengths())
        && logicalPorts.equals(roles)
        && logicalSelectors.equals(labels);
  }

  boolean matchesPatternSource(PatternRunSources.Binding binding) {
    return Objects.equals(patternSource, binding);
  }

  public boolean pendingOutput() {
    return state == State.OUTPUT_PENDING;
  }

  public FrozenItemRun advanced(long tick) {
    return advanced(tick, 1);
  }

  public FrozenItemRun advanced(long tick, int steps) {
    if (steps < 1) {
      throw new IllegalArgumentException("Invalid run advancement steps");
    }
    if (state != State.RUNNING || progress == duration || lastProgressTick == tick) {
      return this;
    }
    return new FrozenItemRun(this, Math.min(duration, Math.addExact(progress, steps)), state, tick);
  }

  public FrozenItemRun afterResourceFailure(long tick) {
    return adjustAfterResourceFailure(tick, failureAction, false);
  }

  /**
   * Applies an explicit server-authorized failure action; STILL advances the
   * attempt fence.
   */
  public FrozenItemRun afterResourceFailure(long tick, RecipeFailureAction action) {
    return adjustAfterResourceFailure(tick, Objects.requireNonNull(action, "action"), true);
  }

  private FrozenItemRun adjustAfterResourceFailure(long tick, RecipeFailureAction action, boolean stampStill) {
    if (state != State.RUNNING) {
      return this;
    }
    int next = switch (action) {
      case RESET -> 0;
      case STILL -> progress;
      case DECREASE -> Math.max(0, progress - 1);
    };
    if (next == progress) {
      return stampStill && lastProgressTick < tick ? new FrozenItemRun(this, next, state, tick) : this;
    }
    return new FrozenItemRun(this, next, state, tick);
  }

  public FrozenItemRun finished() {
    if (pendingOutput()) {
      return this;
    }
    if (progress != duration) {
      throw new IllegalStateException("Recipe has not reached its duration");
    }
    var pending = data.copy();
    pending.put(
        "outputs",
        sampledOutputs(
            data.getCompound("definition").getList("outputs", Tag.TAG_COMPOUND),
            randomSeed,
            parallelInstances));
    pending.putString("state", State.OUTPUT_PENDING.name());
    pending.putInt("progress", progress);
    if (energyOutputPerTick > 0) {
      pending.putInt("energy_output_progress", energyOutputProgress);
    }
    pending.putLong("last_progress_tick", lastProgressTick);
    if (data.getCompound("definition").contains("fluid_outputs")) {
      pending.put(
          "fluid_outputs",
          sampledFluids(
              data.getCompound("definition").getList("fluid_outputs", Tag.TAG_COMPOUND),
              randomSeed,
              parallelInstances,
              false));
    }
    return read(pending);
  }

  public boolean sameRecipe(FrozenItemRun other) {
    return other != null
        && runId.equals(other.runId)
        && machineId.equals(other.machineId)
        && recipeId.equals(other.recipeId)
        && duration == other.duration
        && dynamicLengths.equals(other.dynamicLengths)
        && logicalPorts.equals(other.logicalPorts)
        && logicalSelectors.equals(other.logicalSelectors)
        && Objects.equals(patternSource, other.patternSource)
        && patternInputs.equals(other.patternInputs)
        && (data == other.data || data.getCompound("definition").equals(other.data.getCompound("definition")));
  }

  /**
   * Decode only unpaid output placement claims, never re-resolve consumed inputs
   * or the live recipe manager.
   */
  public List<RecipeCandidate.Output<ItemResourceKey>> outputClaims(HolderLookup.Provider registries) {
    if (!pendingOutput()) {
      throw new IllegalStateException("Outputs have not been generated");
    }
    var rows = data.getList("outputs", Tag.TAG_COMPOUND);
    var result = new ArrayList<RecipeCandidate.Output<ItemResourceKey>>();
    for (int i = 0; i < rows.size(); i++) {
      var row = rows.getCompound(i);
      var stack = ItemStack.SINGLE_ITEM_CODEC
          .parse(registries.createSerializationContext(NbtOps.INSTANCE), row.getCompound("stack")).getOrThrow();
      result.add(
          new RecipeCandidate.Output<>(
              ItemResourceKey.of(stack, registries).withSelector(row.getString("selector")),
              MachineRecipeJson.integer(
                  NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, row.get("amount")),
                  "$.outputs[" + i + "].amount")));
    }
    return List.copyOf(result);
  }

  /**
   * Capacity checks use prepared guaranteed outputs; probabilistic rows stay
   * completion-only.
   */
  List<RecipeCandidate.Output<ItemResourceKey>> startOutputClaims(HolderLookup.Provider registries) {
    var result = new ArrayList<RecipeCandidate.Output<ItemResourceKey>>();
    for (var entry : startOutputRows()) {
      var row = (CompoundTag) entry;
      var stack = ItemStack.SINGLE_ITEM_CODEC
          .parse(registries.createSerializationContext(NbtOps.INSTANCE), row.getCompound("stack")).getOrThrow();
      result.add(
          new RecipeCandidate.Output<>(
              ItemResourceKey.of(stack, registries).withSelector(row.getString("selector")),
              row.getLong("amount")));
    }
    return List.copyOf(result);
  }

  ListTag startOutputRows() {
    var result = new ListTag();
    for (var entry : data.getCompound("definition").getList("outputs", Tag.TAG_COMPOUND)) {
      var row = (CompoundTag) entry;
      if (row.contains("chance") && row.getDouble("chance") != 1) {
        continue;
      }
      var claim = row.copy();
      claim.putLong(
          "amount",
          Math.multiplyExact(row.getLong("amount"), row.getBoolean("parallel_unaffected") ? 1 : parallelInstances));
      claim.remove("chance");
      claim.remove("parallel_unaffected");
      result.add(claim);
    }
    return result;
  }

  /**
   * Final unpaid tick stamp; output callbacks and random identity are not rerun.
   */
  FrozenItemRun atStartTick(long tick) {
    if (progress != 0 || pendingOutput()) {
      throw new IllegalStateException("Only an unpaid prepared run can be stamped for start");
    }
    return new FrozenItemRun(this, 0, State.RUNNING, tick);
  }

  public List<FluidStack> fluidOutputClaims(HolderLookup.Provider registries) {
    if (!pendingOutput()) {
      throw new IllegalStateException("Outputs have not been generated");
    }
    var rows = data.getList("fluid_outputs", Tag.TAG_COMPOUND);
    var result = new ArrayList<FluidStack>();
    for (int i = 0; i < rows.size(); i++) {
      result.add(
          FluidStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), rows.getCompound(i))
              .getOrThrow());
    }
    return List.copyOf(result);
  }

  /**
   * The original full demand remains frozen; only the one-time consumed quantity
   * is sampled.
   */
  List<FluidStack> fluidInputClaims(HolderLookup.Provider registries) {
    var rows = sampledFluids(
        data.getCompound("definition").getList("fluid_inputs", Tag.TAG_COMPOUND),
        randomSeed,
        parallelInstances,
        true);
    var result = new ArrayList<FluidStack>();
    for (int index = 0; index < rows.size(); index++) {
      result.add(
          FluidStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), rows.getCompound(index))
              .getOrThrow());
    }
    return List.copyOf(result);
  }

  public CompoundTag save() {
    var saved = data.copy();
    saved.putInt("schema", schema);
    saved.putInt("progress", progress);
    if (energyOutputPerTick > 0) {
      saved.putInt("energy_output_progress", energyOutputProgress);
    }
    saved.putString("state", state.name());
    saved.putLong("last_progress_tick", lastProgressTick);
    if (pendingOutput()) {
      saved.put(
          "outputs",
          data.contains("outputs") ? data.getList("outputs", Tag.TAG_COMPOUND).copy() : data.getCompound("definition")
              .getList("outputs", Tag.TAG_COMPOUND).copy());
      if (data.getCompound("definition").contains("fluid_outputs")) {
        saved.put(
            "fluid_outputs",
            sampledFluids(
                data.getCompound("definition").getList("fluid_outputs", Tag.TAG_COMPOUND),
                randomSeed,
                parallelInstances,
                false));
      }
    } else {
      saved.remove("outputs");
      saved.remove("fluid_outputs");
    }
    return saved;
  }

  public CompoundTag definition() {
    return data.getCompound("definition").copy();
  }

  private static ListTag sampledOutputs(ListTag source, long seed, int instances) {
    var random = new Random(seed);
    var result = new ListTag();
    for (int i = 0; i < source.size(); i++) {
      var row = source.getCompound(i);
      double chance = row.contains("chance", Tag.TAG_DOUBLE) ? row.getDouble("chance") : 1.0D;
      int attempts = row.getBoolean("parallel_unaffected") ? 1 : instances;
      long successes = chance >= 1 ? attempts : 0;
      if (chance > 0 && chance < 1) {
        for (int attempt = 0; attempt < attempts; attempt++) {
          if (random.nextDouble() < chance) {
            successes++;
          }
        }
      }
      if (successes == 0) {
        continue;
      }
      var copy = row.copy();
      copy.putLong("amount", Math.multiplyExact(row.getLong("amount"), successes));
      copy.remove("chance");
      copy.remove("parallel_unaffected");
      result.add(copy);
    }
    return result;
  }

  private static ListTag sampledFluids(ListTag source, long seed, int instances, boolean input) {
    var random = new Random(seed ^ (input ? 0x52AA06AB184ECB22L : 0x73BFE15FD01F13B7L));
    var result = new ListTag();
    for (int index = 0; index < source.size(); index++) {
      var row = source.getCompound(index);
      double chance = row.contains("chance", Tag.TAG_ANY_NUMERIC) ? row.getDouble("chance") : 1;
      int attempts = row.getBoolean("parallel_unaffected") ? 1 : instances;
      int successes = chance >= 1 ? attempts : 0;
      if (chance > 0 && chance < 1) {
        for (int attempt = 0; attempt < attempts; attempt++) {
          if (random.nextDouble() < chance) {
            successes++;
          }
        }
      }
      if (successes == 0) {
        continue;
      }
      int fullAmount = row.getInt("amount");
      if (fullAmount % attempts != 0) {
        throw new IllegalArgumentException("Frozen fluid quantity is not an exact per-instance demand");
      }
      var copy = row.copy();
      copy.putInt("amount", Math.multiplyExact(fullAmount / attempts, successes));
      copy.remove("chance");
      copy.remove("parallel_unaffected");
      result.add(copy);
    }
    return result;
  }
}
