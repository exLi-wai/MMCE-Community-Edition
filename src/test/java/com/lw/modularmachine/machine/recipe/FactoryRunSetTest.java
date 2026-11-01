package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.factory.FactoryRunSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FactoryRunSetTest {
  @Test
  void emptySetIsImmutableAndRejectsDuplicateOrMissingRuns() {
    var run = FrozenItemRun.read(FrozenItemRunTest.valid());
    var set = FactoryRunSet.empty().add(run);
    assertEquals(1, set.runs().size());
    assertEquals(run.runId(), set.find(run.runId()).orElseThrow().runId());
    assertThrows(IllegalArgumentException.class, () -> set.add(run));
    assertThrows(IllegalArgumentException.class, () -> set.remove(UUID.randomUUID()));
  }

  @Test
  void supportsUncappedRunSetsAndBulkReplacement() {
    var runs = new ArrayList<FrozenItemRun>();
    for (int index = 0; index < 300; index++) {
      var data = FrozenItemRunTest.valid();
      data.putUUID("run_id", new UUID(1, index + 1));
      runs.add(FrozenItemRun.read(data));
    }
    var set = FactoryRunSet.ofRuns(runs);
    var replacements = new HashMap<UUID, FrozenItemRun>();
    for (int index = 0; index < 300; index++) {
      var run = runs.get(index);
      replacements.put(run.runId(), index == 299 ? run.advanced(300L, 20) : run.advanced(index + 1L));
    }

    var replaced = set.replaceAll(replacements);
    assertEquals(300, replaced.runs().size());
    assertEquals(1, replaced.runs().getFirst().progress());
    assertEquals(20, replaced.runs().getLast().progress());
    assertThrows(IllegalArgumentException.class, () -> set.addAll(runs));
  }

  @Test
  void appliesFailureReplacementsAndDiscardAsOneImmutableRunSet() {
    var first = failureRun(new UUID(1, 1), 5, 10);
    var second = failureRun(new UUID(1, 2), 8, 10);
    var set = FactoryRunSet.ofRuns(List.of(first, second));
    var decreased = first.afterResourceFailure(11, RecipeFailureAction.DECREASE);

    var changed = set.applyFailureDecisions(Map.of(first.runId(), decreased), Set.of(second.runId()));

    assertNotSame(set, changed);
    assertEquals(List.of(decreased), changed.runs());
    assertEquals(List.of(first, second), set.runs());
  }

  @Test
  void failureDiscardRejectsUnknownRunsAndPendingOutputs() {
    var run = failureRun(new UUID(2, 1), 5, 10);
    var set = FactoryRunSet.ofRuns(List.of(run));
    assertThrows(IllegalArgumentException.class, () -> set.applyFailureDecisions(Map.of(), Set.of(UUID.randomUUID())));
    var pending = failureRun(new UUID(2, 2), 20, 10).finished();
    var pendingSet = FactoryRunSet.ofRuns(List.of(pending));
    assertThrows(
        IllegalArgumentException.class,
        () -> pendingSet.applyFailureDecisions(Map.of(), Set.of(pending.runId())));
  }

  @Test
  void failureDecisionSetCannotPartiallyResolveUnknownOrConflictingRuns() {
    var first = failureRun(new UUID(3, 1), 5, 10);
    var second = failureRun(new UUID(3, 2), 6, 10);
    var set = FactoryRunSet.ofRuns(List.of(first, second));
    var changed = first.afterResourceFailure(11, RecipeFailureAction.RESET);

    assertThrows(
        IllegalArgumentException.class,
        () -> set.applyFailureDecisions(Map.of(first.runId(), changed), Set.of(first.runId())));
    assertThrows(
        IllegalArgumentException.class,
        () -> set.applyFailureDecisions(Map.of(first.runId(), changed, UUID.randomUUID(), changed), Set.of()));
    assertEquals(List.of(first, second), set.runs());
  }

  private static FrozenItemRun failureRun(UUID id, int progress, long lastTick) {
    var data = FrozenItemRunTest.valid();
    data.putUUID("run_id", id);
    data.putInt("progress", progress);
    data.putLong("last_progress_tick", lastTick);
    data.getCompound("definition").putInt("schema_version", 2);
    data.getCompound("definition").putString("failure_action", "still");

    return FrozenItemRun.read(data);
  }
}
