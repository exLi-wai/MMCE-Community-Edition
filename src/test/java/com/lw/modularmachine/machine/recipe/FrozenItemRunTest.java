package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.recipe.RecipeFailureAction;
import com.lw.modularmachine.machine.PortKind;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrozenItemRunTest {
  private static CompoundTag requirement(String item) {
    var stack = new CompoundTag();
    stack.putString("id", item);
    var requirement = new CompoundTag();
    requirement.put("stack", stack);
    requirement.putLong("amount", 2);
    return requirement;
  }

  static void addPort(CompoundTag source, PortKind kind) {
    var port = new CompoundTag();
    port.putString("id", kind.name().toLowerCase(java.util.Locale.ROOT));
    port.putString("kind", kind.name());
    source.getList("ports", Tag.TAG_COMPOUND).add(port);
  }

  static CompoundTag valid() {
    var definition = new CompoundTag();
    definition.putString("type", MachineRecipeJson.TYPE);
    definition.putString("machine", "mmcece:test");
    definition.putInt("duration", 20);
    definition.putString("failure_action", "still");
    definition.putBoolean("parallelized", true);
    var inputs = new ListTag();
    inputs.add(requirement("minecraft:dirt"));
    definition.put("inputs", inputs);
    var outputs = new ListTag();
    outputs.add(requirement("minecraft:diamond"));
    definition.put("outputs", outputs);
    var run = new CompoundTag();
    run.putInt("schema", 3);
    run.putUUID("run_id", new UUID(1, 2));
    run.putString("machine", "mmcece:test");
    run.putString("recipe", "mmcece:mmcece/test");
    run.putInt("duration", 20);
    run.putInt("progress", 0);
    run.putString("state", "RUNNING");
    run.putBoolean("inputs_committed", true);
    run.putLong("definition_epoch", 5);
    run.putLong("last_progress_tick", -1);
    run.putLong("random_seed", 0);
    run.put("definition", definition);
    var ports = new ListTag();
    var in = new CompoundTag();
    in.putString("id", "in");
    in.putString("kind", "ITEM_INPUT");
    ports.add(in);
    var out = new CompoundTag();
    out.putString("id", "out");
    out.putString("kind", "ITEM_OUTPUT");
    ports.add(out);
    run.put("ports", ports);
    return run;
  }

  @Test
  void readsFrozenMetadataWithoutResolvingLiveRecipes() {
    var run = FrozenItemRun.read(valid());
    assertEquals(new UUID(1, 2), run.runId());
    assertEquals("mmcece:test", run.machineId().toString());
    assertEquals(20, run.duration());
    assertEquals(0, run.progress());
  }

  @Test
  void preservesSchemaTwoEnergyFeesAcrossRunSaveAndRestore() {
    var source = valid();
    source.putInt("schema", 3);
    source.putLong("last_progress_tick", 40);
    source.getCompound("definition").putLong("energy_per_tick", 128);
    addPort(source, PortKind.ENERGY_INPUT);
    var run = FrozenItemRun.read(source);
    var restored = FrozenItemRun.read(run.save());
    assertEquals(128, restored.energyPerTick());
    assertEquals(run.runId(), restored.runId());
  }

  @Test
  void preservesResolvedParallelAndFailureDefaultsAcrossNbtRecovery() {
    var source = valid();
    source.putInt("progress", 5);
    source.putLong("last_progress_tick", 10);
    var definition = source.getCompound("definition");
    definition.putBoolean("parallelized", false);
    definition.putString("failure_action", "reset");

    var saved = FrozenItemRun.read(source).save();
    var restored = FrozenItemRun.read(saved);

    assertFalse(restored.definition().getBoolean("parallelized"));
    assertEquals(0, restored.afterResourceFailure(11).progress());
    assertEquals(saved, restored.save());
    saved.getCompound("definition").putByte("parallelized", (byte) 2);
    assertThrows(IllegalArgumentException.class, () -> FrozenItemRun.read(saved));
  }

  @Test
  void preservesTheFluidFeeDefinitionAcrossRunSaveAndRestore() {
    var source = valid();
    source.putInt("schema", 3);
    source.putLong("last_progress_tick", 40);
    var definition = source.getCompound("definition");
    definition.put("fluid_per_tick", new CompoundTag());
    definition.getCompound("fluid_per_tick").putString("id", "minecraft:water");
    definition.getCompound("fluid_per_tick").putInt("amount", 125);
    addPort(source, PortKind.FLUID_INPUT);
    var restored = FrozenItemRun.read(FrozenItemRun.read(source).save());
    var fee = restored.definition().getCompound("fluid_per_tick");
    assertEquals("minecraft:water", fee.getString("id"));
    assertEquals(125, fee.getInt("amount"));
  }

  @Test
  void copiesInputAndExportedTags() {
    var source = valid();
    var run = FrozenItemRun.read(source);
    source.putInt("progress", 7);
    run.save().putInt("duration", 999);
    run.definition().getList("outputs", Tag.TAG_COMPOUND).getCompound(0).putLong("amount", 999);
    assertEquals(0, run.progress());
    assertEquals(20, run.duration());
    assertEquals(2, run.definition().getList("outputs", Tag.TAG_COMPOUND).getCompound(0).getLong("amount"));
  }

  @Test
  void preservesPendingFluidOutputsAndRejectsAlteredClaims() {
    var source = valid();
    var fluid = new CompoundTag();
    fluid.putString("id", "minecraft:water");
    fluid.putInt("amount", 40);
    var fluids = new ListTag();
    fluids.add(fluid);
    source.getCompound("definition").put("fluid_outputs", fluids);
    addPort(source, PortKind.FLUID_OUTPUT);
    source.putInt("progress", 19);
    var pending = FrozenItemRun.read(source).advanced(41).finished();
    var saved = pending.save();
    assertEquals(40, saved.getList("fluid_outputs", Tag.TAG_COMPOUND).getCompound(0).getInt("amount"));
    assertEquals(saved, FrozenItemRun.read(saved).save());
    saved.getList("fluid_outputs", Tag.TAG_COMPOUND).getCompound(0).putInt("amount", 80);
    assertThrows(IllegalArgumentException.class, () -> FrozenItemRun.read(saved));
    var missing = pending.save();
    missing.remove("fluid_outputs");
    assertThrows(IllegalArgumentException.class, () -> FrozenItemRun.read(missing));
  }

  @Test
  void appliesResetStillAndDecreaseOnlyToRunningProgress() {
    for (var entry : Map.of("reset", 0, "still", 5, "decrease", 4).entrySet()) {
      var source = valid();
      source.putInt("schema", 3);
      source.putLong("last_progress_tick", 10);
      source.putInt("progress", 5);
      var definition = source.getCompound("definition");
      definition.putString("failure_action", entry.getKey());
      var run = FrozenItemRun.read(source);
      assertEquals(entry.getValue(), run.afterResourceFailure(11).progress(), entry.getKey());
      assertEquals(
          run.afterResourceFailure(11).progress(),
          FrozenItemRun.read(run.save()).afterResourceFailure(11).progress());
    }
    var pending = FrozenItemRun.read(valid());
    assertSame(pending, pending.afterResourceFailure(3));
  }

  @Test
  void explicitFailureStillStampsTheAttemptWithoutChangingProgress() {
    var source = valid();
    source.putLong("last_progress_tick", 10);
    source.putInt("progress", 5);
    var definition = source.getCompound("definition");
    definition.putString("failure_action", "reset");
    var run = FrozenItemRun.read(source);

    var explicitStill = run.afterResourceFailure(11, RecipeFailureAction.STILL);
    assertNotSame(run, explicitStill);
    assertEquals(5, explicitStill.progress());
    assertEquals(11, explicitStill.lastProgressTick());
    assertEquals(0, run.afterResourceFailure(11).progress());
  }

  @Test
  void explicitFailureDecisionOverridesFrozenActionWithoutChangingPaidRecipe() {
    var source = valid();
    source.putLong("last_progress_tick", 10);
    source.putInt("progress", 5);
    var definition = source.getCompound("definition");
    definition.putString("failure_action", "reset");
    var run = FrozenItemRun.read(source);

    var decreased = run.afterResourceFailure(11, RecipeFailureAction.DECREASE);

    assertEquals(4, decreased.progress());
    assertEquals(11, decreased.lastProgressTick());
    assertEquals(run.recipeId(), decreased.recipeId());
    assertEquals(run.randomSeed(), decreased.randomSeed());
    assertEquals(0, run.afterResourceFailure(11).progress());
  }

  @Test
  void preservesRandomSeedAcrossFrozenRunRecovery() {
    var source = valid();
    source.putInt("schema", 3);
    source.putLong("last_progress_tick", 1);
    var definition = source.getCompound("definition");
    definition.putLong("random_seed", Long.MAX_VALUE);
    source.putLong("random_seed", Long.MAX_VALUE);
    var run = FrozenItemRun.read(source);
    assertEquals(Long.MAX_VALUE, run.randomSeed());
    assertEquals(Long.MAX_VALUE, FrozenItemRun.read(run.save()).randomSeed());
  }

  @Test
  void freezesChanceResultsOnceAndPreservesThemAcrossPendingRecovery() {
    var source = valid();
    source.putInt("schema", 3);
    source.putLong("last_progress_tick", 1);
    source.putLong("random_seed", 7);
    var definition = source.getCompound("definition");
    definition.putLong("random_seed", 7);
    var output = definition.getList("outputs", Tag.TAG_COMPOUND).getCompound(0);
    output.putDouble("chance", 0.0D);
    var guaranteed = requirement("minecraft:emerald");
    guaranteed.putDouble("chance", 1.0D);
    definition.getList("outputs", Tag.TAG_COMPOUND).add(guaranteed);
    var run = FrozenItemRun.read(source);
    for (int tick = 2; tick <= run.duration() + 1; tick++) {
      run = run.advanced(tick);
    }
    run = run.finished();
    var saved = run.save();
    assertEquals(saved, FrozenItemRun.read(saved).save());
    var result = saved.getList("outputs", Tag.TAG_COMPOUND);
    assertEquals(1, result.size());
    assertEquals("minecraft:emerald", result.getCompound(0).getCompound("stack").getString("id"));
    assertFalse(result.getCompound(0).contains("chance"));
    saved.getList("outputs", Tag.TAG_COMPOUND).add(requirement("minecraft:emerald"));
    assertThrows(IllegalArgumentException.class, () -> FrozenItemRun.read(saved));
  }

  @Test
  void rejectsFutureSchemaAndFields() {
    var root = valid();
    root.putInt("schema", 4);
    assertThrows(IllegalArgumentException.class, () -> FrozenItemRun.read(root));
    var other = valid();
    other.putBoolean("finish_generated", true);
    assertThrows(IllegalArgumentException.class, () -> FrozenItemRun.read(other));
  }

  @Test
  void rejectsUnpaidOrFutureRunStages() {
    var root = valid();
    root.putBoolean("inputs_committed", false);
    assertThrows(IllegalArgumentException.class, () -> FrozenItemRun.read(root));
    var future = valid();
    future.putString("state", "OUTPUT_PENDING");
    assertThrows(IllegalArgumentException.class, () -> FrozenItemRun.read(future));
  }

  @Test
  void checksProgressRangeAndTagTypes() {
    for (int value : new int[]{-1, 21}) {
      var root = valid();
      root.putInt("progress", value);
      assertThrows(IllegalArgumentException.class, () -> FrozenItemRun.read(root));
    }
    var fractional = valid();
    fractional.putDouble("progress", 0.5);
    assertThrows(IllegalArgumentException.class, () -> FrozenItemRun.read(fractional));
    var ok = valid();
    ok.putInt("progress", 4);
    assertEquals(4, FrozenItemRun.read(ok).progress());
  }

  @Test
  void checksDefinitionMachineAndDurationConsistency() {
    var machine = valid();
    machine.putString("machine", "mmcece:other");
    assertThrows(IllegalArgumentException.class, () -> FrozenItemRun.read(machine));
    var duration = valid();
    duration.putInt("duration", 21);
    assertThrows(IllegalArgumentException.class, () -> FrozenItemRun.read(duration));
  }

  @Test
  void rejectsBadEpochOrUuidAndAcceptsFrozenEnergyCost() {
    var epoch = valid();
    epoch.putLong("definition_epoch", -1);
    assertThrows(IllegalArgumentException.class, () -> FrozenItemRun.read(epoch));
    var uuid = valid();
    uuid.putString("run_id", "not a uuid");
    assertThrows(IllegalArgumentException.class, () -> FrozenItemRun.read(uuid));
    var requirement = valid();
    requirement.getCompound("definition").putLong("energy_per_tick", 1);
    addPort(requirement, PortKind.ENERGY_INPUT);
    assertEquals(1L, FrozenItemRun.read(requirement).save().getCompound("definition").getLong("energy_per_tick"));
  }

  @Test
  void preservesUnresolvedItemsWithoutDefaultingToAnotherRecipe() {
    var root = valid();
    root.getCompound("definition").getList("outputs", Tag.TAG_COMPOUND).getCompound(0).getCompound("stack")
        .putString("id", "temporarily_absent:resource");
    assertEquals(root, FrozenItemRun.read(root).save());
  }

  @Test
  void rejectsDuplicateOrMissingLogicalPorts() {
    var duplicate = valid();
    duplicate.getList("ports", Tag.TAG_COMPOUND).add(duplicate.getList("ports", Tag.TAG_COMPOUND).get(0).copy());
    assertThrows(IllegalArgumentException.class, () -> FrozenItemRun.read(duplicate));
    var missing = valid();
    missing.remove("ports");
    assertThrows(IllegalArgumentException.class, () -> FrozenItemRun.read(missing));
    assertEquals(PortKind.ITEM_INPUT, FrozenItemRun.read(valid()).logicalPorts().get("in"));
  }
}
