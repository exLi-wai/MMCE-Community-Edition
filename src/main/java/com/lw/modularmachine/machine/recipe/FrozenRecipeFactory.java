package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecutionRegistry;
import com.lw.modularmachine.api.recipe.RecipeItemCallbacks;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.api.upgrade.UpgradeSnapshot;
import com.mojang.serialization.JsonOps;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import com.lw.modularmachine.api.recipe.RecipeScriptOptions;
import org.jetbrains.annotations.ApiStatus;

/**
 * Builds a frozen paid recipe declaration once, before the resource/run commit.
 */
@ApiStatus.Internal
final class FrozenRecipeFactory {
  private FrozenRecipeFactory() {
  }

  static FrozenRecipeRun freeze(ResourceLocation recipeId, MachineRecipe recipe, long epoch, HolderLookup.Provider registries, List<RecipePortSnapshot> ports, long tick) {
    return freeze(recipeId, recipe, epoch, registries, ports, tick, UpgradeSnapshot.identity(0));
  }

  static FrozenRecipeRun freeze(ResourceLocation recipeId, MachineRecipe recipe, long epoch, HolderLookup.Provider registries, List<RecipePortSnapshot> ports, long tick, UpgradeSnapshot upgrades) {
    return freeze(recipeId, recipe, epoch, registries, ports, tick, upgrades, InputGroup.SHARED);
  }

  static FrozenRecipeRun freeze(ResourceLocation recipeId, MachineRecipe recipe, long epoch, HolderLookup.Provider registries, List<RecipePortSnapshot> ports, long tick, UpgradeSnapshot upgrades, int inputGroup) {
    return freeze(recipeId, recipe, epoch, registries, ports, tick, upgrades, inputGroup, 1);
  }

  static FrozenRecipeRun freeze(ResourceLocation recipeId, MachineRecipe recipe, long epoch, HolderLookup.Provider registries, List<RecipePortSnapshot> ports, long tick, UpgradeSnapshot upgrades, int inputGroup, int parallelInstances) {
    return freeze(recipeId, recipe, epoch, registries, ports, tick, upgrades, inputGroup, parallelInstances, Map.of());
  }

  static FrozenRecipeRun freeze(ResourceLocation recipeId, MachineRecipe recipe, long epoch, HolderLookup.Provider registries, List<RecipePortSnapshot> ports, long tick, UpgradeSnapshot upgrades, int inputGroup, int parallelInstances, Map<String, Integer> dynamicLengths) {
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

  static FrozenRecipeRun freeze(ResourceLocation recipeId, MachineRecipe recipe, long epoch, HolderLookup.Provider registries, List<RecipePortSnapshot> ports, long tick, UpgradeSnapshot upgrades, int inputGroup, int parallelInstances, Map<String, Integer> dynamicLengths, RecipeItemCallbacks.Context callbackContext) {
    if (RecipeTriggeredResources.arithmeticLimit(recipe, parallelInstances, true, upgrades) < parallelInstances) {
      throw new IllegalArgumentException("Combined trigger and per-tick amounts exceed frozen arithmetic limits");
    }
    var encodedDefinition = Recipe.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), recipe)
        .getOrThrow();
    if (!(encodedDefinition instanceof CompoundTag)) {
      throw new IllegalArgumentException("Machine recipe did not encode as a compound");
    }
    var definition = applyUpgrades((CompoundTag) encodedDefinition, upgrades);
    UUID runId = UUID.randomUUID();
    long seed = recipe.randomSeed() == 0 ? (runId.getMostSignificantBits() ^ runId
        .getLeastSignificantBits()) & Long.MAX_VALUE : recipe.randomSeed();
    RecipeRandomItemOutputs.freeze(definition, seed, parallelInstances, registries);
    RecipeResourceExecutionRegistry.freezeDefinition(definition, parallelInstances, registries);
    scaleDefinition(definition, parallelInstances);
    long callbackEpoch = -1;
    var frozenOptions = RecipeScriptOptions.CODEC.codec().parse(NbtOps.INSTANCE, definition).getOrThrow();
    if (!frozenOptions.itemCallbacks().isEmpty()) {
      if (callbackContext == null) {
        throw new IllegalArgumentException("Item callback recipes require a prepared controller context");
      }
      var declaration = NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, definition).getAsJsonObject();
      MachineRecipeJson.restoreRowBooleans(declaration, definition);
      declaration.addProperty("parallelized", definition.getBoolean("parallelized"));
      var prepared = RecipeItemCallbackService.prepareOutputs(declaration, frozenOptions, callbackContext, registries)
          .orElseThrow(() -> new IllegalStateException("Item callback preparation rejected the recipe"));
      definition = (CompoundTag) Recipe.CODEC
          .encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), prepared.definition()).getOrThrow();
      callbackEpoch = prepared.epoch();
    }
    int duration = definition.getInt("duration");
    var tag = new CompoundTag();
    tag.putInt("schema", 3);
    tag.putInt("input_group", InputGroup.require(inputGroup));
    tag.putInt("parallel_instances", parallelInstances);
    tag.putInt("parallel_base", upgrades.parallelLimit());
    tag.putUUID("run_id", runId);
    tag.putString("machine", recipe.machineId().toString());
    tag.putString("recipe", recipeId.toString());
    tag.putInt("duration", duration);
    tag.putInt("progress", 0);
    if (definition.contains("triggered_resources", Tag.TAG_LIST)) {
      tag.putInt("trigger_progress", 0);
    }
    if (recipe.energyOutputPerTick() > 0) {
      tag.putInt("energy_output_progress", 0);
    }
    if (!definition.getCompound("chemicals").getList("per_tick_outputs", Tag.TAG_COMPOUND).isEmpty()
        || !definition.getList("fluid_per_tick_outputs", Tag.TAG_COMPOUND).isEmpty()) {
      tag.putInt("resource_output_progress", 0);
    }
    tag.putString("state", "RUNNING");
    tag.putLong("last_progress_tick", tick);
    tag.putBoolean("inputs_committed", true);
    tag.putLong("definition_epoch", epoch);
    if (callbackEpoch >= 0) {
      tag.putLong("item_callback_epoch", callbackEpoch);
    }
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

    return FrozenRecipeRun.read(tag, registries);
  }

  /** Ordinary batches multiply the already rounded, upgraded per-run fees. */
  static int aggregateTickLimit(int limit, long energyInput, long energyOutput, int fluidInput, UpgradeSnapshot upgrades) {
    limit = factoryTickLimit(limit, energyInput, energyOutput, upgrades);
    try {
      int fluid = Math.toIntExact(upgrades.fluidPerTick(fluidInput));
      if (fluid > 0) {
        limit = Math.min(limit, Integer.MAX_VALUE / fluid);
      }
    } catch (ArithmeticException overflow) {
      // Even one upgraded run cannot be represented by the frozen definition.
      return 0;
    }

    return limit;
  }

  /**
   * Factory ticks sum the separately frozen run fees for each energy selector.
   */
  static int factoryTickLimit(int limit, long energyInput, long energyOutput, UpgradeSnapshot upgrades) {
    try {
      long energy = upgrades.energyPerTick(energyInput);
      if (energy > 0) {
        limit = (int) Math.min(limit, Long.MAX_VALUE / energy);
      }
      if (energyOutput > 0) {
        limit = (int) Math.min(limit, Long.MAX_VALUE / energyOutput);
      }
    } catch (ArithmeticException overflow) {
      return 0;
    }

    return limit;
  }

  static CompoundTag applyUpgrades(CompoundTag source, UpgradeSnapshot upgrades) {
    var definition = source.copy();
    int duration = upgrades.durationFor(definition.getInt("duration"));
    RecipeTriggeredResources.upgrade(definition, definition.getInt("duration"), duration);
    definition.putInt("duration", duration);
    if (definition.contains("energy_per_tick", Tag.TAG_ANY_NUMERIC)) {
      definition.putLong("energy_per_tick", upgrades.energyPerTick(definition.getLong("energy_per_tick")));
    }
    if (definition.contains("fluid_per_tick", Tag.TAG_COMPOUND)) {
      var fluidFee = definition.getCompound("fluid_per_tick");
      fluidFee.putInt("amount", Math.toIntExact(upgrades.fluidPerTick(fluidFee.getInt("amount"))));
    }
    var fluidFees = definition.getList("fluid_per_tick_inputs", Tag.TAG_COMPOUND);
    for (int index = 0; index < fluidFees.size(); index++) {
      var fluidFee = fluidFees.getCompound(index);
      fluidFee.putInt("amount", Math.toIntExact(upgrades.fluidPerTick(fluidFee.getInt("amount"))));
    }

    return definition;
  }

  static void scaleDefinition(CompoundTag definition, int instances) {
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
    scaleRows(definition, "fluid_per_tick_inputs", instances);
    scaleRows(definition, "fluid_per_tick_outputs", instances);
    RecipeTriggeredResources.scale(definition, instances);
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
        if (row.contains("max_amount", Tag.TAG_ANY_NUMERIC) && row.getLong("max_amount") > 0) {
          row.putLong("min_amount", Math.multiplyExact(row.getLong("min_amount"), instances));
          row.putLong("max_amount", amount);
        }
      }
    }
  }

}
