package com.lw.modularmachine.machine.recipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.lw.modularmachine.api.recipe.RecipeFailureAction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

class FrozenEnergyOutputTest {
  private static CompoundTag energyRun() {
    var source = FrozenItemRunTest.valid();
    source.getCompound("definition").putLong("energy_output_per_tick", 64);
    source.putInt("energy_output_progress", 0);
    var output = new CompoundTag();
    output.putString("id", "energy_out");
    output.putString("kind", "ENERGY_OUTPUT");
    source.getList("ports", Tag.TAG_COMPOUND).add(output);
    return source;
  }

  @Test
  void resetDoesNotRepeatPaidEnergyAfterSaveAndRestore() {
    var run = FrozenItemRun.read(energyRun());
    assertEquals(64, run.nextEnergyOutput());
    run = run.advanced(1).advanced(2).advanced(3);
    run = FrozenItemRun.read(run.afterResourceFailure(4, RecipeFailureAction.RESET).save());
    for (int tick = 5; tick < 8; tick++) {
      assertEquals(0, run.nextEnergyOutput());
      run = FrozenItemRun.read(run.advanced(tick).save());
    }
    assertEquals(3, run.progress());
    assertEquals(64, run.nextEnergyOutput());
  }

  @Test
  void decreaseAndFailedTransactionKeepMonotonicPaymentMarker() {
    var before = FrozenItemRun.read(energyRun());
    var proposal = before.advanced(1);
    assertEquals(64, before.nextEnergyOutput(), "Uncommitted proposal cannot mark the original run paid");
    var decreased = proposal.afterResourceFailure(2, RecipeFailureAction.DECREASE);
    assertEquals(0, decreased.nextEnergyOutput());
    assertEquals(64, decreased.advanced(3).nextEnergyOutput());
    assertEquals(1, FrozenItemRun.read(decreased.save()).save().getInt("energy_output_progress"));
  }

  @Test
  void finalOutputAndCorruptMarkersCannotReplayEnergy() {
    var run = FrozenItemRun.read(energyRun()).advanced(1, 20).finished();
    assertEquals(0, run.nextEnergyOutput());
    assertEquals(20, FrozenItemRun.read(run.save()).save().getInt("energy_output_progress"));
    for (int marker : new int[]{-1, 21}) {
      var invalid = energyRun();
      invalid.putInt("energy_output_progress", marker);
      assertThrows(IllegalArgumentException.class, () -> FrozenItemRun.read(invalid));
    }
    var missing = energyRun();
    missing.remove("energy_output_progress");
    assertThrows(IllegalArgumentException.class, () -> FrozenItemRun.read(missing));
    var behind = energyRun();
    behind.putInt("progress", 1);
    assertThrows(IllegalArgumentException.class, () -> FrozenItemRun.read(behind));
  }
}
