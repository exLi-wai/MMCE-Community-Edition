package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.recipe.search.RecipeCandidate;
import java.util.List;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.HashSet;
import java.util.Set;

class RecipeItemQuantityTest {
  @Test
  void exactLongRangeIncludesBothEndpointsWithoutOverflow() {
    var random = RandomSource.create(7);
    long lower = Long.MAX_VALUE - 1;
    var found = new HashSet<Long>();
    for (int attempt = 0; attempt < 100; attempt++) {
      found.add(RecipeItemAmounts.sample(lower, Long.MAX_VALUE, random));
      long full = RecipeItemAmounts.sample(0, Long.MAX_VALUE, random);
      assertTrue(full >= 0);
    }
    assertEquals(Set.of(lower, Long.MAX_VALUE), found);
  }

  @Test
  void inputDrawIsStableAndRespectsFixedParallelRows() {
    var recipe = new RecipeCandidate<>(
        "quantity",
        0,
        List.of(
            new RecipeCandidate.Input<>("variable", 10, 1, false, 2),
            new RecipeCandidate.Input<>("fixed", 5, 1, true, 5),
            new RecipeCandidate.Input<>("retained", 100, 0, false, 0)),
        List.of());
    var sampled = RecipeStartPlan.sampledInputs(recipe, 15, 100);
    assertEquals(sampled, RecipeStartPlan.sampledInputs(recipe, 15, 100));
    assertTrue(sampled.get("variable") >= 200 && sampled.get("variable") <= 1000);
    assertEquals(5, sampled.get("fixed"));
    assertFalse(sampled.containsKey("retained"));
  }

  @Test
  void outputRangeSurvivesPendingSaveAndRejectsTamperedClaim() {
    var source = FrozenRecipeRunTest.valid();
    var row = source.getCompound("definition").getList("outputs", Tag.TAG_COMPOUND).getCompound(0);
    row.putLong("amount", 9);
    row.putLong("min_amount", 2);
    row.putLong("max_amount", 9);
    source.putInt("parallel_instances", 50);
    var run = FrozenRecipeRun.read(source).advanced(1, 20).finished();
    var outputs = run.save().getList("outputs", Tag.TAG_COMPOUND);
    long quantity = outputs.getCompound(0).getLong("amount");
    assertTrue(quantity >= 100 && quantity <= 450);
    assertEquals(run.save(), FrozenRecipeRun.read(run.save()).save());
    var forged = run.save();
    forged.getList("outputs", Tag.TAG_COMPOUND).getCompound(0).putLong("amount", quantity + 1);
    assertThrows(IllegalArgumentException.class, () -> FrozenRecipeRun.read(forged));
  }
}
