package com.lw.modularmachine.machine.recipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

class PreparedOutputClaimsTest {
  @Test
  void startClaimsMatchFrozenCompletionAmountsForParallelAndFixedRows() {
    var source = FrozenItemRunTest.valid();
    source.putInt("parallel_instances", 4);
    var outputs = source.getCompound("definition").getList("outputs", Tag.TAG_COMPOUND);
    var fixed = outputs.getCompound(0).copy();
    fixed.putBoolean("parallel_unaffected", true);
    fixed.putString("selector", "fixed");
    outputs.add(fixed);

    var run = FrozenItemRun.read(source);
    var claims = run.startOutputRows();
    assertEquals(8, claims.getCompound(0).getLong("amount"));
    assertEquals(2, claims.getCompound(1).getLong("amount"));
    assertEquals("fixed", claims.getCompound(1).getString("selector"));
    assertEquals(claims, run.advanced(1, run.duration()).finished().save().getList("outputs", Tag.TAG_COMPOUND));
  }

  @Test
  void probabilityRowsDoNotClaimStartSpaceOrMutateFrozenOutputs() {
    var source = FrozenItemRunTest.valid();
    var outputs = source.getCompound("definition").getList("outputs", Tag.TAG_COMPOUND);
    var probable = outputs.getCompound(0).copy();
    probable.putDouble("chance", 0.5D);
    outputs.add(probable);
    var absent = probable.copy();
    absent.putDouble("chance", 0);
    outputs.add(absent);

    var run = FrozenItemRun.read(source);
    var claims = run.startOutputRows();
    assertEquals(1, claims.size());
    assertFalse(claims.getCompound(0).contains("chance"));
    claims.getCompound(0).putLong("amount", 99);
    assertEquals(2, run.startOutputRows().getCompound(0).getLong("amount"));
    assertEquals(outputs, run.definition().getList("outputs", Tag.TAG_COMPOUND));
  }
}
