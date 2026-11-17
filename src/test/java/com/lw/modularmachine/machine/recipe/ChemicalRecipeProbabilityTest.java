package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.recipe.ChemicalRecipeData;
import com.lw.modularmachine.api.recipe.ChemicalRequirement;
import com.lw.modularmachine.api.recipe.RecipeFailureAction;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.mojang.serialization.JsonOps;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChemicalRecipeProbabilityTest {
  private static final ResourceLocation HYDROGEN = ResourceLocation.parse("mekanism:hydrogen");

  @Test
  void zeroOneAndFractionalDrawsKeepExactLongQuantitiesAndStableRetry() {
    long amount = 9007199254740993L;
    var rows = List.of(
        new ChemicalRequirement(HYDROGEN, amount, "zero", 0),
        new ChemicalRequirement(HYDROGEN, amount, "one", 1),
        new ChemicalRequirement(HYDROGEN, 800, "random", 0.5));
    var first = ChemicalRecipeSampling.sample(rows, 42, 1, RecipeModifier.Io.INPUT, -1);
    assertEquals(first, ChemicalRecipeSampling.sample(rows, 42, 1, RecipeModifier.Io.INPUT, -1));
    assertFalse(first.stream().anyMatch(row -> row.selector().equals("zero")));
    assertEquals(amount, first.stream().filter(row -> row.selector().equals("one")).findFirst().orElseThrow().amount());
    assertTrue(first.stream().allMatch(row -> row.chance() == 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> ChemicalRecipeSampling.sample(rows, 42, 2, RecipeModifier.Io.INPUT, -1));
  }

  @Test
  void frozenParallelDrawsNeverExceedFullDemandAndProgressChangesTheDraw() {
    var rows = List.of(new ChemicalRequirement(HYDROGEN, 10000, "feed", 0.5));
    var first = ChemicalRecipeSampling.sample(rows, 7, 100, RecipeModifier.Io.INPUT, 0);
    assertEquals(first, ChemicalRecipeSampling.sample(rows, 7, 100, RecipeModifier.Io.INPUT, 0));
    assertNotEquals(first, ChemicalRecipeSampling.sample(rows, 7, 100, RecipeModifier.Io.INPUT, 1));
    long consumed = first.stream().mapToLong(ChemicalRequirement::amount).sum();
    assertTrue(consumed >= 0 && consumed <= 10000);
    assertEquals(0, consumed % 100);
  }

  @Test
  void resetAndRestartDoNotReplayTickOutputPositionsOrCompletionDraws() {
    var source = tickOutputRun();
    var run = FrozenRecipeRun.read(source);
    assertTrue(run.canEmitTickOutputs());
    var proposal = run.advanced(1);
    assertTrue(run.canEmitTickOutputs(), "An uncommitted successor cannot mark the original output paid");
    run = proposal.advanced(2).advanced(3).afterResourceFailure(4, RecipeFailureAction.RESET);
    run = FrozenRecipeRun.read(run.save());
    assertEquals(3, run.tickOutputProgress());
    for (int tick = 5; tick < 8; tick++) {
      assertFalse(run.canEmitTickOutputs());
      run = FrozenRecipeRun.read(run.advanced(tick).save());
    }
    assertTrue(run.canEmitTickOutputs());
    var pending = run.advanced(8, 17).finished();
    assertEquals(pending.chemicalOutputs(), FrozenRecipeRun.read(pending.save()).chemicalOutputs());
    assertFalse(pending.canEmitTickOutputs());
  }

  @Test
  void invalidOrMissingTickOutputWatermarksCannotLoad() {
    for (int marker : new int[]{-1, 21}) {
      var source = tickOutputRun();
      source.putInt("resource_output_progress", marker);
      assertThrows(IllegalArgumentException.class, () -> FrozenRecipeRun.read(source));
    }
    var missing = tickOutputRun();
    missing.remove("resource_output_progress");
    assertThrows(IllegalArgumentException.class, () -> FrozenRecipeRun.read(missing));
    var behind = tickOutputRun();
    behind.putInt("progress", 1);
    assertThrows(IllegalArgumentException.class, () -> FrozenRecipeRun.read(behind));
  }

  private static CompoundTag tickOutputRun() {
    var source = FrozenRecipeRunTest.valid();
    var chemicals = new ChemicalRecipeData(
        List.of(),
        List.of(new ChemicalRequirement(HYDROGEN, 100, "product", 0.5)),
        List.of(),
        List.of(new ChemicalRequirement(HYDROGEN, 32, "product", 0.5)));
    source.getCompound("definition").put(
        "chemicals",
        JsonOps.INSTANCE.convertTo(
            NbtOps.INSTANCE,
            ChemicalRecipeData.CODEC.encodeStart(JsonOps.INSTANCE, chemicals).getOrThrow()));
    source.putInt("resource_output_progress", 0);
    var port = new CompoundTag();
    port.putString("id", "chemical_product");
    port.putString("kind", "CHEMICAL_OUTPUT");
    port.putString("selector", "product");
    source.getList("ports", Tag.TAG_COMPOUND).add(port);

    return source;
  }
}
