package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.recipe.search.ResourceSnapshot;
import com.lw.modularmachine.machine.recipe.search.RecipeCandidate;
import com.lw.modularmachine.machine.recipe.search.RecipePlanner;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ItemRunStateTest {
  private static FrozenItemRun complete() {
    var run = FrozenItemRun.read(FrozenItemRunTest.valid());
    for (int tick = 0; tick < 20; tick++) {
      run = run.advanced(tick);
    }
    return run.finished();
  }

  @Test
  void transitionsAreImmutableAndGettersMatchSavedProgress() {
    var before = FrozenItemRun.read(FrozenItemRunTest.valid());
    var after = before.advanced(10);
    assertEquals(0, before.progress());
    assertEquals(1, after.progress());
    assertEquals(1, after.save().getInt("progress"));
    assertEquals(0, before.save().getInt("progress"));
    assertSame(after, after.advanced(10));
  }

  @Test
  void currentRunRetainsSameTickStampOnReload() {
    var run = FrozenItemRun.read(FrozenItemRunTest.valid()).advanced(10);
    assertEquals(3, run.save().getInt("schema"));
    var loaded = FrozenItemRun.read(run.save());
    assertSame(loaded, loaded.advanced(10));
    assertEquals(2, loaded.advanced(11).progress());
    assertEquals(run.runId(), loaded.runId());
  }

  @Test
  void finishRequiresDurationAndCreatesOneStableLongAmountBuffer() {
    var start = FrozenItemRun.read(FrozenItemRunTest.valid());
    assertThrows(IllegalStateException.class, start::finished);
    var pending = complete();
    assertTrue(pending.pendingOutput());
    assertSame(pending, pending.finished());
    assertSame(pending, pending.advanced(100));
    assertEquals(
        pending.definition().getList("outputs", Tag.TAG_COMPOUND),
        pending.save().getList("outputs", Tag.TAG_COMPOUND));
    assertTrue(FrozenItemRun.read(pending.save()).pendingOutput());
  }

  @Test
  void malformedPendingStateNeverChangesOrLosesTheOriginalPayload() {
    var valid = complete().save();
    var altered = valid.copy();
    altered.getList("outputs", Tag.TAG_COMPOUND).getCompound(0).putLong("amount", 3);
    assertThrows(IllegalArgumentException.class, () -> FrozenItemRun.read(altered));
    var missing = valid.copy();
    missing.remove("outputs");
    assertThrows(IllegalArgumentException.class, () -> FrozenItemRun.read(missing));
    var early = valid.copy();
    early.putInt("progress", 19);
    assertThrows(IllegalArgumentException.class, () -> FrozenItemRun.read(early));
    assertEquals(2, valid.getList("outputs", Tag.TAG_COMPOUND).getCompound(0).getLong("amount"));
  }

  @Test
  void integerMaximumDurationCannotWrapProgress() {
    var data = FrozenItemRunTest.valid();
    data.putInt("duration", Integer.MAX_VALUE);
    data.putInt("progress", Integer.MAX_VALUE - 1);
    data.getCompound("definition").putInt("duration", Integer.MAX_VALUE);
    var run = FrozenItemRun.read(data).advanced(1);
    assertEquals(Integer.MAX_VALUE, run.progress());
    assertSame(run, run.advanced(2));
    assertTrue(run.finished().pendingOutput());
  }

  @Test
  void outputOnlyPlacementSharesAllSpaceAndDoesNotConsumeInputs() {
    var candidate = RecipeCandidate
        .outputOnly("buffer", List.of(new RecipeCandidate.Output<>("a", 1), new RecipeCandidate.Output<>("b", 1)));
    var blocked = new ResourceSnapshot<>(
        0,
        Map.<String, Long>of(),
        List.of(new ResourceSnapshot.OutputSlot<String>(null, 0, 64)));
    assertEquals(
        RecipePlanner.Status.NO_MATCH,
        RecipePlanner.prepareOutputs(blocked, candidate).advance(1000).status());
    var free = new ResourceSnapshot<>(
        0,
        Map.<String, Long>of(),
        List.of(
            new ResourceSnapshot.OutputSlot<String>(null, 0, 64),
            new ResourceSnapshot.OutputSlot<String>(null, 0, 64)));
    var plan = RecipePlanner.prepareOutputs(free, candidate).advance(1000).plan().orElseThrow();
    assertTrue(plan.consumed().isEmpty());
    assertEquals(2, plan.insertions().size());
  }

  @Test
  void impossibleLongOutputIsRejectedWithoutExpandingStacksOrExponentialSearch() {
    var candidate = RecipeCandidate.outputOnly("large", List.of(new RecipeCandidate.Output<>("a", Long.MAX_VALUE)));
    var view = new ResourceSnapshot<>(
        0,
        Map.<String, Long>of(),
        List.of(new ResourceSnapshot.OutputSlot<String>(null, 0, 64)));
    var result = RecipePlanner.prepareOutputs(view, candidate).advance(100);
    assertEquals(RecipePlanner.Status.NO_MATCH, result.status());
    assertTrue(result.workUsed() < 100);
  }
}
