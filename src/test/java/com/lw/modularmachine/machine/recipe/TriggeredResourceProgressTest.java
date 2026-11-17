package com.lw.modularmachine.machine.recipe;

import static org.junit.jupiter.api.Assertions.*;
import com.lw.modularmachine.api.recipe.RecipeFailureAction;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.recipe.TriggeredResourceData;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import com.lw.modularmachine.machine.PortKind;

class TriggeredResourceProgressTest {
  private static TriggeredResourceData.Row row(int tick, boolean repeatable, long amount, double chance) {
    return new TriggeredResourceData.Row(
        "chemical",
        RecipeModifier.Io.OUTPUT,
        tick,
        repeatable,
        amount,
        chance,
        "",
        Optional.empty(),
        Optional.empty(),
        Optional.of(ResourceLocation.parse("mekanism:hydrogen")),
        false,
        "exact");
  }

  private static CompoundTag source() {
    var source = FrozenRecipeRunTest.valid();
    var row = new CompoundTag();
    row.putString("resource", "chemical");
    row.putString("io", "output");
    row.putInt("tick", 3);
    row.putBoolean("repeatable", false);
    row.putLong("amount", 100);
    row.putString("chemical", "mekanism:hydrogen");
    var rows = new ListTag();
    rows.add(row);
    source.getCompound("definition").put("triggered_resources", rows);
    source.putInt("trigger_progress", 0);
    FrozenRecipeRunTest.addPort(source, PortKind.CHEMICAL_OUTPUT);
    return source;
  }

  @Test
  void oneShotTriggerKeepsItsFenceAcrossResetSaveAndRestart() {
    var run = FrozenRecipeRun.read(source()).advanced(1).advanced(2);
    assertTrue(RecipeTriggeredResources.due(run, row(3, false, 100, 1)));
    var committed = run.advanced(3);
    assertTrue(
        RecipeTriggeredResources.due(run, row(3, false, 100, 1)),
        "An uncommitted successor cannot mark its original run triggered");
    var reset = FrozenRecipeRun.read(committed.afterResourceFailure(4, RecipeFailureAction.RESET).save());
    reset = reset.advanced(5).advanced(6);
    assertFalse(RecipeTriggeredResources.due(reset, row(3, false, 100, 1)));
    assertTrue(
        RecipeTriggeredResources.due(reset, row(3, true, 100, 1)),
        "Repeatable explicitly permits IO when reset revisits its position");
    assertEquals(3, reset.triggerProgress());
  }

  @Test
  void finalPositionAndCompletionPersistTheFence() {
    var run = FrozenRecipeRun.read(source()).advanced(1, 19);
    assertTrue(RecipeTriggeredResources.due(run, row(20, false, 100, 1)));
    var finished = run.advanced(2).finished();
    assertFalse(RecipeTriggeredResources.due(finished, row(20, false, 100, 1)));
    assertEquals(20, FrozenRecipeRun.read(finished.save()).triggerProgress());
  }

  @Test
  void malformedOrMissingFencesCannotLoad() {
    var missing = source();
    missing.remove("trigger_progress");
    assertThrows(IllegalArgumentException.class, () -> FrozenRecipeRun.read(missing));
    for (int fence : new int[]{-1, 21}) {
      var invalid = source();
      invalid.putInt("trigger_progress", fence);
      assertThrows(IllegalArgumentException.class, () -> FrozenRecipeRun.read(invalid));
    }
    var behind = source();
    behind.putInt("progress", 1);
    assertThrows(IllegalArgumentException.class, () -> FrozenRecipeRun.read(behind));
  }

  @Test
  void frozenParallelSamplingAndTimeScalingAreStableAndExact() {
    var source = source();
    source.putInt("parallel_instances", 100);
    var run = FrozenRecipeRun.read(source);
    var row = row(3, false, 10000, 0.5);
    long first = RecipeTriggeredResources.sampledAmount(run, row, 0);
    assertEquals(first, RecipeTriggeredResources.sampledAmount(run, row, 0));
    assertEquals(0, first % 100);
    assertTrue(first >= 0 && first <= 10000);
    assertEquals(2, RecipeTriggeredResources.scaledTick(3, 20, 10));
    assertEquals(10, RecipeTriggeredResources.scaledTick(20, 20, 10));
    assertEquals(1, RecipeTriggeredResources.scaledTick(1, 20, 1));
  }
}
