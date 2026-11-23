package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.recipe.RecipeRunState;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.recipe.resource.RecipeResources;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecutionRegistry;
import com.lw.modularmachine.machine.recipe.search.RecipeCandidate;
import com.lw.modularmachine.api.recipe.RecipeSelectors;
import com.lw.modularmachine.api.recipe.RecipeFailureAction;
import com.lw.modularmachine.api.recipe.ChemicalRecipeData;
import com.lw.modularmachine.api.recipe.ChemicalRequirement;
import com.lw.modularmachine.api.recipe.RecipeExecutionOptions;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.structure.CompiledStructure;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import com.lw.modularmachine.api.recipe.TriggeredResourceData;
import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.api.recipe.FluidRequirement;

/**
 * Immutable paid-run state. Payload is shared privately between transitions; no
 * live recipe/world reference.
 */
public final class FrozenRecipeRun {

  private final CompoundTag data;
  private final FrozenRecipeBindings bindings;
  private final ResourceCache resourceCache;

  private static final class ResourceCache {
    RecipeResources value;
    RecipeResources ordinaryTick;
    RecipeResources replayedTick;
    RecipeResourceExecutionRegistry.TickStrategies tickStrategies;
    TriggeredResourceData triggers;
  }
  private final UUID runId;
  private final ResourceLocation machineId, recipeId;
  private final int duration, progress;
  private final long energyPerTick;
  private final long energyOutputPerTick;
  private final int energyOutputProgress;
  private final int resourceOutputProgress;
  private final int triggerProgress;
  private final boolean tickResourceOutputs;
  private final RecipeExecutionOptions executionOptions;
  private final long itemCallbackEpoch;
  private final RecipeFailureAction failureAction;
  private final long randomSeed;
  private final CompoundTag fluidPerTickData;
  private final FluidStack fluidPerTick;
  private final RecipeRunState state;
  private final long lastProgressTick;
  private final RecipeSelectors selectors;
  private final ChemicalRecipeData chemicals;
  private final int parallelInstances;
  private final int parallelBase;

  private FrozenRecipeRun(CompoundTag source, HolderLookup.Provider registries) {
    resourceCache = new ResourceCache();
    var header = FrozenRecipeRunCodec.readHeader(source);
    bindings = new FrozenRecipeBindings(source);
    var definition = FrozenRecipeRunCodec.readDefinition(source, header, bindings, registries);
    FrozenRecipeRunCodec.validateOutputs(source, header);
    runId = header.runId();
    machineId = header.machineId();
    recipeId = header.recipeId();
    duration = header.duration();
    progress = header.progress();
    state = header.state();
    lastProgressTick = header.lastProgressTick();
    randomSeed = header.randomSeed();
    itemCallbackEpoch = header.itemCallbackEpoch();
    parallelInstances = header.parallelInstances();
    parallelBase = header.parallelBase();
    energyPerTick = definition.energyPerTick();
    energyOutputPerTick = definition.energyOutputPerTick();
    energyOutputProgress = definition.energyOutputProgress();
    resourceOutputProgress = definition.resourceOutputProgress();
    tickResourceOutputs = definition.tickResourceOutputs();
    executionOptions = definition.executionOptions();
    failureAction = definition.failureAction();
    selectors = definition.selectors();
    chemicals = definition.chemicals();
    fluidPerTickData = definition.fluidPerTickData();
    fluidPerTick = definition.fluidPerTick();
    data = source.copy();
    resourceCache.triggers = registries == null ? null : TriggeredResourceData.CODEC.parse(
        registries.createSerializationContext(NbtOps.INSTANCE),
        source.getCompound("definition").getList("triggered_resources", Tag.TAG_COMPOUND)).getOrThrow();
    triggerProgress = source.getInt("trigger_progress");
    boolean triggered = !source.getCompound("definition").getList("triggered_resources", Tag.TAG_COMPOUND).isEmpty();
    if (source.contains("trigger_progress") && !source.contains("trigger_progress", Tag.TAG_INT)
        || triggered
            && (!source.contains("trigger_progress", Tag.TAG_INT)
                || triggerProgress < progress
                || triggerProgress > duration)
        || !triggered && triggerProgress != 0) {
      throw new IllegalArgumentException("Missing or invalid trigger progress");
    }
    if (registries != null) {
      // Validate all extension demands before accepting paid state; transitions share
      // this cache.
      resourceData(registries);
    }
  }

  private FrozenRecipeRun(FrozenRecipeRun before, int progress, RecipeRunState state, long tick) {
    resourceCache = before.resourceCache;
    bindings = before.bindings;
    data = before.data;
    runId = before.runId;
    machineId = before.machineId;
    recipeId = before.recipeId;
    duration = before.duration;
    energyPerTick = before.energyPerTick;
    energyOutputPerTick = before.energyOutputPerTick;
    energyOutputProgress = energyOutputPerTick > 0 ? Math.max(before.energyOutputProgress, progress) : 0;
    resourceOutputProgress = before.tickResourceOutputs ? Math.max(before.resourceOutputProgress, progress) : 0;
    triggerProgress = Math.max(before.triggerProgress, progress);
    tickResourceOutputs = before.tickResourceOutputs;
    executionOptions = before.executionOptions;
    itemCallbackEpoch = before.itemCallbackEpoch;
    failureAction = before.failureAction;
    randomSeed = before.randomSeed;
    fluidPerTickData = before.fluidPerTickData;
    fluidPerTick = before.fluidPerTick;
    selectors = before.selectors;
    chemicals = before.chemicals;
    parallelInstances = before.parallelInstances;
    parallelBase = before.parallelBase;
    this.progress = progress;
    this.state = state;
    lastProgressTick = tick;
  }

  public static FrozenRecipeRun read(CompoundTag data) {
    return new FrozenRecipeRun(data, null);
  }

  public static FrozenRecipeRun read(CompoundTag data, HolderLookup.Provider registries) {
    return new FrozenRecipeRun(data, registries);
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
    return state == RecipeRunState.RUNNING
        && progress < duration
        && progress >= energyOutputProgress ? energyOutputPerTick : 0;
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

  /**
   * Output positions stay emitted through RESET/DECREASE and complete restart.
   */
  public boolean canEmitTickOutputs() {
    return state == RecipeRunState.RUNNING && progress < duration && progress >= resourceOutputProgress;
  }

  public int tickOutputProgress() {
    return resourceOutputProgress;
  }

  public int triggerProgress() {
    return triggerProgress;
  }

  public TriggeredResourceData triggeredResources() {
    return resourceCache.triggers == null ? TriggeredResourceData.NONE : resourceCache.triggers;
  }

  @ApiStatus.Internal
  public RecipeResources effectiveTickResources(HolderLookup.Provider registries) {
    var original = resourceData(registries);
    if (triggeredResources().rows().stream().anyMatch(row -> RecipeTriggeredResources.due(this, row))) {
      return RecipeTriggeredResources.effective(this, original);
    }
    synchronized (resourceCache) {
      if (canEmitTickOutputs()) {
        if (resourceCache.ordinaryTick == null) {
          resourceCache.ordinaryTick = RecipeTriggeredResources.effective(this, original);
        }
        return resourceCache.ordinaryTick;
      }
      if (resourceCache.replayedTick == null) {
        resourceCache.replayedTick = RecipeTriggeredResources.effective(this, original);
      }
      return resourceCache.replayedTick;
    }
  }

  @ApiStatus.Internal
  public RecipeResourceExecutionRegistry.TickStrategies tickStrategies(HolderLookup.Provider registries) {
    synchronized (resourceCache) {
      if (resourceCache.tickStrategies == null) {
        var original = resourceData(registries);
        resourceCache.tickStrategies = RecipeResourceExecutionRegistry.tickStrategies(original, triggeredResources());
      }
      return resourceCache.tickStrategies;
    }
  }

  public FluidStack fluidPerTick(HolderLookup.Provider registries) {
    if (fluidPerTickData.isEmpty()) {
      return resourceData(registries).get(RecipeResourceDefinitions.FLUID).perTick();
    }

    return fluidPerTick == null ? FrozenRecipeRunCodec.decodeFluidPerTick(fluidPerTickData, registries) : fluidPerTick
        .copy();
  }

  public List<ChemicalRequirement> chemicalPerTick() {
    return chemicals.perTick();
  }

  public List<ChemicalRequirement> chemicalOutputs() {
    if (!pendingOutput()) {
      throw new IllegalStateException("Outputs have not been generated");
    }

    return ChemicalRecipeSampling
        .sample(chemicals.outputs(), randomSeed, parallelInstances, RecipeModifier.Io.OUTPUT, -1);
  }

  public int progress() {
    return progress;
  }

  public RecipeRunState state() {
    return state;
  }

  public long lastProgressTick() {
    return lastProgressTick;
  }

  public Map<String, PortKind> logicalPorts() {
    return bindings.logicalPorts();
  }

  public RecipeSelectors selectors() {
    return selectors;
  }

  public int inputGroup() {
    return bindings.inputGroup();
  }

  public int parallelInstances() {
    return parallelInstances;
  }

  public int parallelBase() {
    return parallelBase;
  }

  /** A paid run never silently switches its input routing after GUI edits. */
  @ApiStatus.Internal
  public boolean matchesInputGroups(RecipeExecutionTicket ticket) {
    return bindings.matchesInputGroups(ticket);
  }

  public boolean matchesBindings(MachineDefinition definition) {
    return machineId.equals(definition.id()) && bindings.matchesDefinition(definition, null);
  }

  public boolean matchesBindings(CompiledStructure pattern) {
    return machineId.equals(pattern.definition.id()) && bindings.matchesPattern(pattern);
  }

  boolean matchesPatternSource(PatternRunSources.Binding binding) {
    return bindings.matchesPatternSource(binding);
  }

  public boolean pendingOutput() {
    return state == RecipeRunState.OUTPUT_PENDING;
  }

  public FrozenRecipeRun advanced(long tick) {
    return advanced(tick, 1);
  }

  public FrozenRecipeRun advanced(long tick, int steps) {
    if (steps < 1) {
      throw new IllegalArgumentException("Invalid run advancement steps");
    }
    if (state != RecipeRunState.RUNNING || progress == duration || lastProgressTick == tick) {
      return this;
    }
    return new FrozenRecipeRun(this, Math.min(duration, Math.addExact(progress, steps)), state, tick);
  }

  public FrozenRecipeRun afterResourceFailure(long tick) {
    return adjustAfterResourceFailure(tick, failureAction, false);
  }

  /**
   * Applies an explicit server-authorized failure action; STILL advances the
   * attempt fence.
   */
  public FrozenRecipeRun afterResourceFailure(long tick, RecipeFailureAction action) {
    return adjustAfterResourceFailure(tick, Objects.requireNonNull(action, "action"), true);
  }

  private FrozenRecipeRun adjustAfterResourceFailure(long tick, RecipeFailureAction action, boolean stampStill) {
    if (state != RecipeRunState.RUNNING) {
      return this;
    }
    int next = switch (action) {
      case RESET -> 0;
      case STILL -> progress;
      case DECREASE -> Math.max(0, progress - 1);
    };
    if (next == progress) {
      return stampStill && lastProgressTick < tick ? new FrozenRecipeRun(this, next, state, tick) : this;
    }
    return new FrozenRecipeRun(this, next, state, tick);
  }

  public FrozenRecipeRun finished() {
    if (pendingOutput()) {
      return this;
    }
    if (progress != duration) {
      throw new IllegalStateException("Recipe has not reached its duration");
    }
    var pending = data.copy();
    pending.put(
        "outputs",
        FrozenRecipeSampling.sampledOutputs(
            data.getCompound("definition").getList("outputs", Tag.TAG_COMPOUND),
            randomSeed,
            parallelInstances));
    pending.putString("state", RecipeRunState.OUTPUT_PENDING.name());
    pending.putInt("progress", progress);
    if (!data.getCompound("definition").getList("triggered_resources", Tag.TAG_COMPOUND).isEmpty()) {
      pending.putInt("trigger_progress", triggerProgress);
    }
    if (energyOutputPerTick > 0) {
      pending.putInt("energy_output_progress", energyOutputProgress);
    }
    if (tickResourceOutputs) {
      pending.putInt("resource_output_progress", resourceOutputProgress);
    }
    pending.putLong("last_progress_tick", lastProgressTick);
    if (data.getCompound("definition").contains("fluid_outputs")) {
      pending.put(
          "fluid_outputs",
          FrozenRecipeSampling.sampledFluids(
              data.getCompound("definition").getList("fluid_outputs", Tag.TAG_COMPOUND),
              randomSeed,
              parallelInstances,
              false));
    }
    return read(pending);
  }

  public boolean sameRecipe(FrozenRecipeRun other) {
    return other != null
        && runId.equals(other.runId)
        && machineId.equals(other.machineId)
        && recipeId.equals(other.recipeId)
        && duration == other.duration
        && bindings.sameLayout(other.bindings)
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
                  "$.outputs[" + i + "].amount"),
              false,
              row.getBoolean("ignore_output_check")));
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
              row.getLong("amount"),
              false,
              row.getBoolean("ignore_output_check")));
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
  FrozenRecipeRun atStartTick(long tick) {
    if (progress != 0 || pendingOutput()) {
      throw new IllegalStateException("Only an unpaid prepared run can be stamped for start");
    }
    return new FrozenRecipeRun(this, 0, RecipeRunState.RUNNING, tick);
  }

  public List<FluidStack> fluidOutputClaims(HolderLookup.Provider registries) {
    return fluidOutputRequirements(registries).stream().map(FluidRequirement::stack).toList();
  }

  public List<FluidRequirement> fluidOutputRequirements(HolderLookup.Provider registries) {
    if (!pendingOutput()) {
      throw new IllegalStateException("Outputs have not been generated");
    }
    var rows = data.getList("fluid_outputs", Tag.TAG_COMPOUND);
    var result = new ArrayList<FluidRequirement>();
    for (int i = 0; i < rows.size(); i++) {
      result.add(
          FluidRequirement.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), rows.getCompound(i))
              .getOrThrow());
    }
    return List.copyOf(result);
  }

  /**
   * The original full demand remains frozen; only the one-time consumed quantity
   * is sampled.
   */
  List<FluidStack> fluidInputClaims(HolderLookup.Provider registries) {
    var rows = FrozenRecipeSampling.sampledFluids(
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
    var saved = FrozenRecipeRunCodec.save(this, data, energyOutputProgress, resourceOutputProgress);
    if (!data.getCompound("definition").getList("triggered_resources", Tag.TAG_COMPOUND).isEmpty()) {
      saved.putInt("trigger_progress", triggerProgress);
    }
    return saved;
  }

  public CompoundTag definition() {
    return data.getCompound("definition").copy();
  }

  /**
   * Internal, once-decoded resource demands shared by immutable run transitions.
   */
  @ApiStatus.Internal
  public RecipeResources resourceData(HolderLookup.Provider registries) {
    Objects.requireNonNull(registries, "registries");
    synchronized (resourceCache) {
      if (resourceCache.triggers == null) {
        resourceCache.triggers = TriggeredResourceData.CODEC.parse(
            registries.createSerializationContext(NbtOps.INSTANCE),
            data.getCompound("definition").getList("triggered_resources", Tag.TAG_COMPOUND)).getOrThrow();
      }
      if (resourceCache.value == null) {
        resourceCache.value = RecipeResourceDefinitions.CODEC.codec()
            .parse(registries.createSerializationContext(NbtOps.INSTANCE), data.getCompound("definition")).getOrThrow();
      }

      return resourceCache.value;
    }
  }

}
