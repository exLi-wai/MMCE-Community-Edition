package com.lw.modularmachine.machine.network.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PatternResourceLedgerTest {
  @Test
  void paidTicksInAnotherReceiptPreserveWaitingSearchDependencies() {
    var ledger = new PatternResourceLedger<String>();
    var running = UUID.randomUUID();
    var waiting = UUID.randomUUID();
    var run = UUID.randomUUID();
    assertTrue(ledger.receive(running, Map.of("fluid", 1000L)));
    assertTrue(ledger.receive(waiting, Map.of("item", 2L)));
    assertTrue(ledger.receive(null, Map.of("item", 1L)));
    assertTrue(ledger.bindRun(running, run));
    var captured = ledger.snapshot();
    for (int tick = 0; tick < 10; tick++) {
      assertTrue(ledger.consumeForRun(running, run, Map.of("fluid", 10L)));
      assertTrue(ledger.matchesShare(captured, waiting));
      assertTrue(ledger.matchesShare(captured, null));
      assertFalse(ledger.matchesShare(captured, running));
    }
    // A waiting order still has to revalidate its own claim at admission.
    assertTrue(ledger.bindRun(waiting, UUID.randomUUID()));
    assertFalse(ledger.matchesShare(captured, waiting));
  }

  @Test
  void cancellationAndUnownedChangesInvalidateOnlyTheRelevantShare() {
    var ledger = new PatternResourceLedger<String>();
    var job = UUID.randomUUID();
    assertTrue(ledger.receive(job, Map.of("item", 2L)));
    assertTrue(ledger.receive(null, Map.of("item", 2L)));
    var captured = ledger.snapshot();
    assertTrue(ledger.consume(null, Map.of("item", 1L)));
    assertFalse(ledger.matchesShare(captured, null));
    assertTrue(ledger.matchesShare(captured, job));
    assertTrue(ledger.finish(job, false));
    assertFalse(ledger.matchesShare(captured, job));
  }

  @Test
  void cancellationReturnsOnlyItsShareAndOnlyTheActualAcceptedAmount() {
    var ledger = new PatternResourceLedger<String>();
    var a = UUID.randomUUID();
    var b = UUID.randomUUID();
    assertTrue(ledger.receive(a, Map.of("iron", 40L)));
    assertTrue(ledger.receive(b, Map.of("iron", 30L)));
    assertTrue(ledger.receive(null, Map.of("iron", 5L)));
    assertTrue(ledger.finish(a, false));
    assertFalse(ledger.finish(a, false));
    assertFalse(ledger.receive(a, Map.of("iron", 1L)));
    assertEquals(40, ledger.returnable(a, "iron"));
    assertTrue(ledger.settleReturn(a, "iron", 25));
    assertEquals(15, ledger.returnable(a, "iron"));
    assertEquals(Map.of("iron", 50L), ledger.physical());
    assertEquals(Map.of("iron", 30L), ledger.available(b));
    assertEquals(Map.of("iron", 5L), ledger.available(null));
    var before = ledger.snapshot();
    assertFalse(ledger.settleReturn(a, "iron", 16));
    assertEquals(before, ledger.snapshot());
    assertTrue(ledger.settleReturn(a, "iron", 15));
    assertEquals(Map.of("iron", 35L), ledger.physical());
  }

  @Test
  void incompleteOrdersAndSlotsNeverPoolTheirMaterials() {
    var slot = new PatternResourceLedger<String>();
    var otherSlot = new PatternResourceLedger<String>();
    var a = UUID.randomUUID();
    var b = UUID.randomUUID();
    slot.receive(a, Map.of("iron", 2L));
    slot.receive(b, Map.of("gold", 2L));
    otherSlot.receive(a, Map.of("gold", 2L));
    var needed = Map.of("iron", 2L, "gold", 2L);
    assertFalse(slot.consume(a, needed));
    assertFalse(slot.consume(b, needed));
    assertFalse(otherSlot.consume(a, needed));
    assertEquals(Map.of("iron", 2L, "gold", 2L), slot.physical());
    slot.receive(a, Map.of("gold", 2L));
    assertTrue(slot.consume(a, needed));
    assertEquals(Map.of("gold", 2L), slot.physical());
    assertEquals(Map.of("gold", 2L), slot.available(b));
  }

  @Test
  void canceledPaidRunRetainsItsTickSourceUntilRelease() {
    var ledger = new PatternResourceLedger<String>();
    var job = UUID.randomUUID();
    var run = UUID.randomUUID();
    ledger.receive(job, Map.of("item", 4L, "fluid", 1000L));
    assertTrue(ledger.consume(job, Map.of("item", 4L)));
    assertTrue(ledger.bindRun(job, run));
    assertFalse(ledger.bindRun(job, run));
    ledger.finish(job, false);
    assertFalse(ledger.consume(job, Map.of("fluid", 100L)));
    assertFalse(ledger.bindRun(job, UUID.randomUUID()));
    assertEquals(0, ledger.returnable(job, "fluid"));
    assertTrue(ledger.consumeForRun(job, run, Map.of("fluid", 100L)));
    assertFalse(ledger.consumeForRun(job, UUID.randomUUID(), Map.of("fluid", 100L)));
    assertTrue(ledger.releaseRun(job, run));
    assertEquals(900, ledger.returnable(job, "fluid"));
    assertTrue(ledger.settleReturn(job, "fluid", 900));
    assertTrue(ledger.empty());
  }

  @Test
  void canceledParallelRunsShareLongTickStockAndReturnOnlyAfterTheLastRelease() {
    var ledger = new PatternResourceLedger<String>();
    var canceled = UUID.randomUUID();
    var other = UUID.randomUUID();
    var first = UUID.randomUUID();
    var second = UUID.randomUUID();
    assertTrue(ledger.receive(canceled, Map.of("fluid", 6_000_000_000L, "item", 8L)));
    assertTrue(ledger.receive(other, Map.of("fluid", 700L)));
    assertTrue(ledger.receive(null, Map.of("fluid", 300L)));
    assertTrue(ledger.bindRun(canceled, first));
    assertTrue(ledger.bindRun(canceled, second));
    assertTrue(ledger.consume(canceled, Map.of("item", 8L)));
    assertTrue(ledger.finish(canceled, false));
    assertTrue(ledger.consumeForRun(canceled, first, Map.of("fluid", 2_500_000_000L)));
    assertTrue(ledger.releaseRun(canceled, first));
    assertEquals(0, ledger.returnable(canceled, "fluid"));
    assertFalse(ledger.settleReturn(canceled, "fluid", 1));
    assertFalse(ledger.consumeForRun(canceled, first, Map.of("fluid", 1L)));
    assertTrue(ledger.consumeForRun(canceled, second, Map.of("fluid", 2_500_000_000L)));

    var restored = new PatternResourceLedger<>(ledger.snapshot());
    assertEquals(Map.of("fluid", 1_000_001_000L), restored.physical());
    assertEquals(0, restored.returnable(canceled, "fluid"));
    assertTrue(restored.releaseRun(canceled, second));
    assertEquals(1_000_000_000L, restored.returnable(canceled, "fluid"));
    assertTrue(restored.settleReturn(canceled, "fluid", 300_000_000L));
    assertTrue(restored.settleReturn(canceled, "fluid", 700_000_000L));
    assertEquals(Map.of("fluid", 1000L), restored.physical());
    assertEquals(Map.of("fluid", 700L), restored.available(other));
    assertEquals(Map.of("fluid", 300L), restored.available(null));
    assertEquals(restored.physical(), new PatternResourceLedger<>(restored.snapshot()).physical());
  }

  @Test
  void failedSharedTickKeepsEveryRunHoldAndResourceInTheCommittedGeneration() {
    var committed = new PatternResourceLedger<String>();
    var job = UUID.randomUUID();
    var first = UUID.randomUUID();
    var second = UUID.randomUUID();
    committed.receive(job, Map.of("fluid", 1000L, "chemical", 2000L));
    committed.bindRun(job, first);
    committed.bindRun(job, second);
    committed.finish(job, false);
    var before = committed.snapshot();

    // The first fee and release are prepared, but a later participant fails.
    // The owner must discard this entire generation, including its run edits.
    var rejected = committed.copy();
    assertTrue(rejected.consumeForRun(job, first, Map.of("fluid", 600L, "chemical", 800L)));
    assertTrue(rejected.releaseRun(job, first));
    var beforeFailure = rejected.snapshot();
    assertFalse(rejected.consumeForRun(job, second, Map.of("fluid", 500L, "chemical", 1L)));
    assertEquals(beforeFailure, rejected.snapshot());
    assertEquals(before, committed.snapshot());
    assertTrue(committed.hasRun(first));
    assertTrue(committed.hasRun(second));
    assertEquals(0, committed.returnable(job, "fluid"));

    var accepted = committed.copy();
    assertTrue(accepted.consumeForRun(job, first, Map.of("fluid", 400L, "chemical", 800L)));
    assertTrue(accepted.consumeForRun(job, second, Map.of("fluid", 400L, "chemical", 800L)));
    assertTrue(accepted.releaseRun(job, first));
    assertTrue(accepted.releaseRun(job, second));
    assertEquals(Map.of("fluid", 200L, "chemical", 400L), accepted.physical());
    assertEquals(200L, accepted.returnable(job, "fluid"));
    assertEquals(400L, accepted.returnable(job, "chemical"));
    assertEquals(accepted.snapshot(), new PatternResourceLedger<>(accepted.snapshot()).snapshot());
  }

  @Test
  void overflowRejectsTheWholeBatchIncludingOtherResourceTypes() {
    var ledger = new PatternResourceLedger<String>();
    var job = UUID.randomUUID();
    assertTrue(ledger.receive(job, Map.of("item", Long.MAX_VALUE)));
    var before = ledger.snapshot();
    assertFalse(ledger.receive(UUID.randomUUID(), Map.of("item", 1L, "fluid", 1000L)));
    assertEquals(before, ledger.snapshot());
    assertTrue(ledger.consume(job, Map.of("item", 1L)));
    before = ledger.snapshot();
    // Lifetime delivered count must not wrap even after some stock was spent.
    assertFalse(ledger.receive(job, Map.of("item", 1L)));
    assertEquals(before, ledger.snapshot());
    assertThrows(IllegalArgumentException.class, () -> ledger.receive(job, Map.of("item", -1L)));
    assertThrows(IllegalArgumentException.class, () -> ledger.consume(job, Map.of()));
  }

  @Test
  void completionPreservesSurplusAndManualReturnAllowsLaterNewJobs() {
    var ledger = new PatternResourceLedger<String>();
    var job = UUID.randomUUID();
    ledger.receive(job, Map.of("item", 12L));
    ledger.finish(job, true);
    assertEquals(0, ledger.returnable(job, "item"));
    assertTrue(ledger.available(job).isEmpty());
    ledger.requestReturn();
    assertFalse(ledger.receive(UUID.randomUUID(), Map.of("item", 1L)));
    assertTrue(ledger.settleReturn(job, "item", 12));
    assertTrue(ledger.completeReturn());
    assertTrue(ledger.receive(UUID.randomUUID(), Map.of("item", 1L)));
    assertFalse(ledger.receive(job, Map.of("item", 1L)));
  }

  @Test
  void privateCopyAndRestorationPreservePhysicalAndOwnershipBalances() {
    var original = new PatternResourceLedger<String>();
    var job = UUID.randomUUID();
    var run = UUID.randomUUID();
    original.receive(job, Map.of("components_a", 2147483648L, "components_b", 3L));
    original.bindRun(job, run);
    var working = original.copy();
    working.consumeForRun(job, run, Map.of("components_a", 2L));
    working.finish(job, false);
    assertEquals(2147483648L, original.available(job).get("components_a"));
    var restored = new PatternResourceLedger<>(working.snapshot());
    assertEquals(working.snapshot(), restored.snapshot());
    assertTrue(restored.hasRun(run));
    assertEquals(0, restored.returnable(job, "components_a"));
    restored.releaseRun(job, run);
    assertEquals(2147483646L, restored.returnable(job, "components_a"));
    assertEquals(3, restored.returnable(job, "components_b"));
    assertThrows(UnsupportedOperationException.class, () -> restored.physical().clear());
  }

  @Test
  void corruptBalancesAndDuplicateRunSourcesAreRejected() {
    var run = UUID.randomUUID();
    var receipt = new PatternResourceLedger.Receipt<>(
        PatternResourceLedger.JobStatus.ACTIVE,
        Map.of("item", 8L),
        Map.of("item", 5L),
        Set.of(run));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PatternResourceLedger.Snapshot<>(
            Map.of("item", 9L),
            Map.of(),
            Map.of(UUID.randomUUID(), receipt),
            Set.of(),
            false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PatternResourceLedger.Snapshot<>(
            Map.of("item", 5L),
            Map.of(),
            Map.of(UUID.randomUUID(), receipt),
            Set.of(run),
            false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PatternResourceLedger.Receipt<>(
            PatternResourceLedger.JobStatus.ACTIVE,
            Map.of("item", 4L),
            Map.of("item", 5L),
            Set.of()));
  }
}
