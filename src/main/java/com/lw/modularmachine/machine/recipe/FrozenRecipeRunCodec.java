package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.recipe.RecipeRunState;
import com.lw.modularmachine.api.recipe.RecipeSelectors;
import com.lw.modularmachine.api.recipe.RecipeFailureAction;
import com.lw.modularmachine.api.recipe.ChemicalRecipeData;
import com.lw.modularmachine.api.recipe.RecipeExecutionOptions;
import com.mojang.serialization.JsonOps;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.ApiStatus;

/** Schema 3 decoding and persistence checks, without live recipe lookup. */
@ApiStatus.Internal
final class FrozenRecipeRunCodec {
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
      "energy_output_progress",
      "resource_output_progress",
      "trigger_progress");

  private FrozenRecipeRunCodec() {
  }

  record Header(
      int schema,
      UUID runId,
      ResourceLocation machineId,
      ResourceLocation recipeId,
      int duration,
      int progress,
      RecipeRunState state,
      long lastProgressTick,
      long randomSeed,
      long itemCallbackEpoch,
      int parallelInstances,
      int parallelBase) {
  }

  record Definition(
      long energyPerTick,
      long energyOutputPerTick,
      int energyOutputProgress,
      int resourceOutputProgress,
      boolean tickResourceOutputs,
      RecipeExecutionOptions executionOptions,
      RecipeFailureAction failureAction,
      RecipeSelectors selectors,
      ChemicalRecipeData chemicals,
      CompoundTag fluidPerTickData,
      FluidStack fluidPerTick) {
  }

  static Header readHeader(CompoundTag source) {
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
    int schema = source.getInt("schema");
    if (source.contains("item_callback_epoch")
        && (!source.contains("item_callback_epoch", Tag.TAG_LONG) || source.getLong("item_callback_epoch") < 0)) {
      throw new IllegalArgumentException("Invalid prepared item callback epoch");
    }
    long itemCallbackEpoch = source.contains("item_callback_epoch") ? source.getLong("item_callback_epoch") : -1;
    int parallelInstances = source.contains("parallel_instances") ? source.getInt("parallel_instances") : 1;
    if (parallelInstances < 1
        || source.contains("parallel_instances") && !source.contains("parallel_instances", Tag.TAG_INT)) {
      throw new IllegalArgumentException("Invalid parallel instances");
    }
    int parallelBase = source.contains("parallel_base") ? source.getInt("parallel_base") : 1;
    if (parallelBase < 1 || source.contains("parallel_base") && !source.contains("parallel_base", Tag.TAG_INT)) {
      throw new IllegalArgumentException("Invalid frozen parallel baseline");
    }
    UUID runId = source.getUUID("run_id");
    ResourceLocation machineId = ResourceLocation.parse(source.getString("machine"));
    ResourceLocation recipeId = ResourceLocation.parse(source.getString("recipe"));
    int duration = source.getInt("duration");
    int progress = source.getInt("progress");
    if (duration <= 0 || progress < 0 || progress > duration) {
      throw new IllegalArgumentException("Invalid run progress");
    }
    RecipeRunState state = RecipeRunState.valueOf(source.getString("state"));
    if (!source.contains("last_progress_tick", Tag.TAG_LONG)) {
      throw new IllegalArgumentException("Missing run tick stamp");
    }
    long lastProgressTick = source.getLong("last_progress_tick");
    if (!source.contains("random_seed", Tag.TAG_LONG) || source.getLong("random_seed") < 0) {
      throw new IllegalArgumentException("Missing or invalid run random seed");
    }
    return new Header(
        schema,
        runId,
        machineId,
        recipeId,
        duration,
        progress,
        state,
        lastProgressTick,
        source.getLong("random_seed"),
        itemCallbackEpoch,
        parallelInstances,
        parallelBase);
  }

  static Definition readDefinition(CompoundTag source, Header run, FrozenRecipeBindings bindings, HolderLookup.Provider registries) {
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
    RecipeSelectors selectors = header.selectors();
    ChemicalRecipeData chemicals = declaration.has("chemicals") ? ChemicalRecipeData.CODEC
        .parse(JsonOps.INSTANCE, declaration.get("chemicals")).getOrThrow() : ChemicalRecipeData.NONE;
    bindings.validateResources(declaration, selectors, chemicals);
    if (!definition.getString("type").equals(MachineRecipeJson.TYPE)
        || !header.machine().equals(run.machineId().toString())
        || header.duration() != run.duration()) {
      throw new IllegalArgumentException("Run/definition mismatch");
    }
    long energyPerTick = header.energyPerTick();
    long energyOutputPerTick = header.energyOutputPerTick();
    if (energyOutputPerTick > 0 && !source.contains("energy_output_progress", Tag.TAG_INT)
        || source.contains("energy_output_progress") && !source.contains("energy_output_progress", Tag.TAG_INT)) {
      throw new IllegalArgumentException("Missing or invalid energy output progress");
    }
    int energyOutputProgress = source.getInt("energy_output_progress");
    if (energyOutputProgress < 0
        || energyOutputProgress > run.duration()
        || energyOutputPerTick > 0 && energyOutputProgress < run.progress()
        || energyOutputPerTick == 0 && energyOutputProgress != 0) {
      throw new IllegalArgumentException("Invalid energy output progress");
    }
    boolean tickResourceOutputs = !chemicals.perTickOutputs().isEmpty()
        || definition.contains("fluid_per_tick_outputs", Tag.TAG_LIST)
            && !definition.getList("fluid_per_tick_outputs", Tag.TAG_COMPOUND).isEmpty();
    if (tickResourceOutputs && !source.contains("resource_output_progress", Tag.TAG_INT)
        || source.contains("resource_output_progress") && !source.contains("resource_output_progress", Tag.TAG_INT)) {
      throw new IllegalArgumentException("Missing or invalid tick resource output progress");
    }
    int resourceOutputProgress = source.getInt("resource_output_progress");
    if (resourceOutputProgress < 0
        || resourceOutputProgress > run.duration()
        || tickResourceOutputs && resourceOutputProgress < run.progress()
        || !tickResourceOutputs && resourceOutputProgress != 0) {
      throw new IllegalArgumentException("Invalid tick resource output progress");
    }
    RecipeExecutionOptions executionOptions = RecipeExecutionOptions.CODEC.parse(JsonOps.INSTANCE, declaration)
        .getOrThrow();
    RecipeFailureAction failureAction = RecipeFailureAction.parse(header.failureAction());
    CompoundTag fluidPerTickData;
    FluidStack fluidPerTick;
    if (definition.contains("fluid_per_tick", Tag.TAG_COMPOUND)) {
      fluidPerTickData = definition.getCompound("fluid_per_tick").copy();
      fluidPerTick = registries == null ? null : decodeFluidPerTick(fluidPerTickData, registries);
    } else {
      fluidPerTickData = new CompoundTag();
      fluidPerTick = null;
    }
    return new Definition(
        energyPerTick,
        energyOutputPerTick,
        energyOutputProgress,
        resourceOutputProgress,
        tickResourceOutputs,
        executionOptions,
        failureAction,
        selectors,
        chemicals,
        fluidPerTickData,
        fluidPerTick);
  }

  static void validateOutputs(CompoundTag source, Header header) {
    var definition = source.getCompound("definition");
    var state = header.state();
    int progress = header.progress();
    int duration = header.duration();
    long randomSeed = header.randomSeed();
    int parallelInstances = header.parallelInstances();
    if (state == RecipeRunState.OUTPUT_PENDING) {
      if (progress != duration
          || !source.contains("outputs", Tag.TAG_LIST)
          || !FrozenRecipeSampling
              .sampledOutputs(definition.getList("outputs", Tag.TAG_COMPOUND), randomSeed, parallelInstances)
              .equals(source.getList("outputs", Tag.TAG_COMPOUND))
          || definition.contains("fluid_outputs")
              && (!source.contains("fluid_outputs", Tag.TAG_LIST)
                  || !FrozenRecipeSampling.sampledFluids(
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
  }

  static FluidStack decodeFluidPerTick(CompoundTag data, HolderLookup.Provider registries) {
    return FluidStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), data).getOrThrow();
  }

  static CompoundTag save(FrozenRecipeRun run, CompoundTag data, int energyOutputProgress, int resourceOutputProgress) {
    var saved = data.copy();
    saved.putInt("schema", 3);
    saved.putInt("progress", run.progress());
    if (run.energyOutputPerTick() > 0) {
      saved.putInt("energy_output_progress", energyOutputProgress);
    }
    if (!data.getCompound("definition").getCompound("chemicals").getList("per_tick_outputs", Tag.TAG_COMPOUND).isEmpty()
        || !data.getCompound("definition").getList("fluid_per_tick_outputs", Tag.TAG_COMPOUND).isEmpty()) {
      saved.putInt("resource_output_progress", resourceOutputProgress);
    }
    saved.putString("state", run.state().name());
    saved.putLong("last_progress_tick", run.lastProgressTick());
    if (run.pendingOutput()) {
      saved.put(
          "outputs",
          data.contains("outputs") ? data.getList("outputs", Tag.TAG_COMPOUND).copy() : data.getCompound("definition")
              .getList("outputs", Tag.TAG_COMPOUND).copy());
      if (data.getCompound("definition").contains("fluid_outputs")) {
        saved.put(
            "fluid_outputs",
            FrozenRecipeSampling.sampledFluids(
                data.getCompound("definition").getList("fluid_outputs", Tag.TAG_COMPOUND),
                run.randomSeed(),
                run.parallelInstances(),
                false));
      }
    } else {
      saved.remove("outputs");
      saved.remove("fluid_outputs");
    }
    return saved;
  }

}
