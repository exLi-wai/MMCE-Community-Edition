package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.factory.FactoryUpgradeSnapshot;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
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
      "input_group",
      "parallel_instances",
      "parallel_base",
      "outputs",
      "fluid_outputs",
      "last_progress_tick",
      "random_seed");
  private final CompoundTag data;
  private final UUID runId;
  private final ResourceLocation machineId, recipeId;
  private final int schema, duration, progress;
  private final long energyPerTick;
  private final RecipeFailureAction failureAction;
  private final long randomSeed;
  private final CompoundTag fluidPerTickData;
  private final FluidStack fluidPerTick;
  private final State state;
  private final long lastProgressTick;
  private final Map<String, PortKind> logicalPorts;
  private final Map<String, String> logicalSelectors;
  private final RecipeSelectors selectors;
  private final int inputGroup;
  private final Map<String, Integer> logicalInputGroups;
  private final int parallelInstances;
  private final int parallelBase;

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
    var ports = source.getList("ports", Tag.TAG_COMPOUND);
    var mapped = new LinkedHashMap<String, PortKind>();
    var mappedSelectors = new LinkedHashMap<String, String>();
    var mappedGroups = new LinkedHashMap<String, Integer>();
    if (ports.isEmpty() || ports.size() > 4096) {
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
      if (group != InputGroup.SHARED && kind != PortKind.ITEM_INPUT && kind != PortKind.FLUID_INPUT) {
        throw new IllegalArgumentException("Non-groupable frozen port");
      }
      mappedGroups.put(id, group);
    }
    if (!mapped.containsValue(PortKind.ITEM_INPUT) || !mapped.containsValue(PortKind.ITEM_OUTPUT)) {
      throw new IllegalArgumentException("Item run lacks logical item ports");
    }
    logicalPorts = Map.copyOf(mapped);
    logicalSelectors = Map.copyOf(mappedSelectors);
    logicalInputGroups = Map.copyOf(mappedGroups);
    if (inputGroup > 0 && !mappedGroups.containsValue(inputGroup)) {
      throw new IllegalArgumentException("Frozen input group has no mapped input");
    }
    var definition = source.getCompound("definition");
    if (definition.contains("modifiers")) {
      throw new IllegalArgumentException("Frozen recipe contains unresolved modifiers");
    }
    var header = MachineRecipeJson.validate(NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, definition));
    selectors = header.selectors();
    if (!definition.getString("type").equals(MachineRecipeJson.TYPE)
        || !header.machine().equals(machineId.toString())
        || header.duration() != duration) {
      throw new IllegalArgumentException("Run/definition mismatch");
    }
    energyPerTick = header.energyPerTick();
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
                  || !definition.getList("fluid_outputs", Tag.TAG_COMPOUND)
                      .equals(source.getList("fluid_outputs", Tag.TAG_COMPOUND)))
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
    failureAction = before.failureAction;
    randomSeed = before.randomSeed;
    fluidPerTickData = before.fluidPerTickData;
    fluidPerTick = before.fluidPerTick;
    logicalPorts = before.logicalPorts;
    logicalSelectors = before.logicalSelectors;
    selectors = before.selectors;
    inputGroup = before.inputGroup;
    logicalInputGroups = before.logicalInputGroups;
    parallelInstances = before.parallelInstances;
    parallelBase = before.parallelBase;
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
    var encodedDefinition = Recipe.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), recipe)
        .getOrThrow();
    if (!(encodedDefinition instanceof CompoundTag)) {
      throw new IllegalArgumentException("Machine recipe did not encode as a compound");
    }
    var definition = applyFactoryUpgrades((CompoundTag) encodedDefinition, upgrades);
    scaleDefinition(definition, parallelInstances);
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
    tag.putString("state", "RUNNING");
    tag.putLong("last_progress_tick", tick);
    tag.putBoolean("inputs_committed", true);
    tag.putLong("definition_epoch", epoch);
    long seed = recipe.randomSeed() == 0 ? (runId.getMostSignificantBits() ^ runId
        .getLeastSignificantBits()) & Long.MAX_VALUE : recipe.randomSeed();
    tag.putLong("random_seed", seed);
    tag.put("definition", definition);
    var mapping = new ListTag();
    for (var port : ports) {
      var entry = new CompoundTag();
      entry.putString("id", port.partId());
      entry.putString("kind", port.kind().name());
      entry.putInt("input_group", port.inputGroup());
      if (!port.selector().isEmpty()) {
        entry.putString("selector", port.selector());
      }
      mapping.add(entry);
    }
    tag.put("ports", mapping);

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
    scaleRows(definition, "inputs", instances);
    scaleRows(definition, "fluid_inputs", instances);
    scaleRows(definition, "fluid_outputs", instances);
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

    return machineId.equals(definition.id()) && logicalPorts.equals(roles) && logicalSelectors.equals(labels);
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
    pending.putLong("last_progress_tick", lastProgressTick);
    if (data.getCompound("definition").contains("fluid_outputs")) {
      pending.put("fluid_outputs", data.getCompound("definition").getList("fluid_outputs", Tag.TAG_COMPOUND).copy());
    }
    return read(pending);
  }

  public boolean sameRecipe(FrozenItemRun other) {
    return other != null
        && runId.equals(other.runId)
        && machineId.equals(other.machineId)
        && recipeId.equals(other.recipeId)
        && duration == other.duration
        && logicalPorts.equals(other.logicalPorts)
        && logicalSelectors.equals(other.logicalSelectors)
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

  public List<FluidStack> fluidOutputClaims(HolderLookup.Provider registries) {
    if (!pendingOutput()) {
      throw new IllegalStateException("Outputs have not been generated");
    }
    var rows = data.getCompound("definition").getList("fluid_outputs", Tag.TAG_COMPOUND);
    var result = new ArrayList<FluidStack>();
    for (int i = 0; i < rows.size(); i++) {
      result.add(
          FluidStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), rows.getCompound(i))
              .getOrThrow());
    }
    return List.copyOf(result);
  }

  public CompoundTag save() {
    var saved = data.copy();
    saved.putInt("schema", schema);
    saved.putInt("progress", progress);
    saved.putString("state", state.name());
    saved.putLong("last_progress_tick", lastProgressTick);
    if (pendingOutput()) {
      saved.put(
          "outputs",
          data.contains("outputs") ? data.getList("outputs", Tag.TAG_COMPOUND).copy() : data.getCompound("definition")
              .getList("outputs", Tag.TAG_COMPOUND).copy());
      if (data.getCompound("definition").contains("fluid_outputs")) {
        saved.put("fluid_outputs", data.getCompound("definition").getList("fluid_outputs", Tag.TAG_COMPOUND).copy());
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
      long successes = chance >= 1 ? instances : 0;
      if (chance > 0 && chance < 1) {
        for (int attempt = 0; attempt < instances; attempt++) {
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
      result.add(copy);
    }
    return result;
  }
}
