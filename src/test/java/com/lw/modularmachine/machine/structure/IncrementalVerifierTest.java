package com.lw.modularmachine.machine.structure;

import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IncrementalVerifierTest {
  @Test
  void independentFullScanAlsoClosesOnThrowingOrNullPredicates() {
    var throwing = FullScanReference.scan(2, index -> {
      throw new IllegalStateException("opaque callback");
    });
    assertEquals(VerificationFailure.CHECK_ERROR, throwing.failure());
    assertEquals(VerificationState.INVALID, throwing.state());
    assertEquals(1, throwing.checks());
    assertEquals(VerificationFailure.CHECK_ERROR, FullScanReference.scan(2, index -> null).failure());
  }

  @Test
  void throwingOrNullPredicateClosesProductionAndCanBeRetried() {
    var verifier = new IncrementalVerifier(new int[][]{{0, 1}}, 0);
    assertEquals(1, verifier.advance(8, 0, 20, index -> {
      throw new IllegalStateException("opaque predicate");
    }));
    assertEquals(VerificationFailure.CHECK_ERROR, verifier.failure());
    assertFalse(verifier.canCommit());
    verifier.markAllDirty(1);
    assertEquals(1, verifier.advance(8, 1, 20, index -> null));
    assertEquals(VerificationFailure.CHECK_ERROR, verifier.failure());
    assertFalse(verifier.canCommit());
    verifier.markAllDirty(2);
    assertEquals(2, verifier.advance(8, 2, 20, index -> CheckResult.MATCH));
    assertTrue(verifier.canCommit());
  }

  @Test
  void pendingNeverGrantsProduction() {
    var verifier = new IncrementalVerifier(new int[][]{{0, 1}, {2}}, 0);
    assertFalse(verifier.canCommit());
    assertEquals(1, verifier.advance(1, 0, 20, i -> CheckResult.MATCH));
    assertEquals(VerificationState.PENDING, verifier.state());
    assertFalse(verifier.canCommit());
    verifier.advance(2, 1, 20, i -> CheckResult.MATCH);
    assertTrue(verifier.canCommit());
    verifier.markDirty(1, 2);
    assertFalse(verifier.canCommit());
    assertEquals(1, verifier.advance(10, 2, 20, i -> CheckResult.MATCH));
    assertTrue(verifier.canCommit());
  }

  @Test
  void unrelatedRepairCannotEraseKnownMismatch() {
    var verifier = new IncrementalVerifier(new int[][]{{0}, {1}}, 0);
    verifier.advance(10, 0, 20, i -> i == 0 ? CheckResult.MISMATCH : CheckResult.MATCH);
    assertEquals(VerificationState.INVALID, verifier.state());
    verifier.markDirty(1, 1);
    verifier.advance(10, 1, 20, i -> CheckResult.MATCH);
    assertFalse(verifier.canCommit());
    assertEquals(VerificationFailure.MISMATCH, verifier.failure());
    verifier.markDirty(0, 2);
    verifier.advance(10, 2, 20, i -> CheckResult.MATCH);
    assertTrue(verifier.canCommit());
  }

  @Test
  void repeatedChangesDoNotExtendRoundDeadline() {
    var verifier = new IncrementalVerifier(new int[][]{{0, 1}}, 0);
    for (int tick = 1; tick < 10; tick++) {
      verifier.markDirty(0, tick);
    }
    verifier.advance(10, 11, 10, i -> CheckResult.MATCH);
    assertEquals(VerificationFailure.VERIFICATION_TIMEOUT, verifier.failure());
    assertFalse(verifier.canCommit());
  }

  @Test
  void unavailableIsNotReportedAsBadBlock() {
    var verifier = new IncrementalVerifier(new int[][]{{0}}, 0);
    verifier.advance(1, 0, 20, i -> CheckResult.UNAVAILABLE);
    assertEquals(VerificationFailure.CHUNK_UNAVAILABLE, verifier.failure());
    assertFalse(verifier.canCommit());
    verifier.markAllDirty(1);
    verifier.advance(1, 1, 20, i -> CheckResult.MATCH);
    assertTrue(verifier.canCommit());
  }

  @Test
  void reentrantDirtyCannotPublishStaleSuccess() {
    var verifier = new IncrementalVerifier(new int[][]{{0, 1}}, 0);
    AtomicBoolean once = new AtomicBoolean();
    verifier.advance(10, 0, 20, i -> {
      if (once.compareAndSet(false, true)) {
        verifier.markDirty(0, 0);
      }
      return CheckResult.MATCH;
    });
    assertEquals(VerificationState.PENDING, verifier.state());
    assertFalse(verifier.canCommit());
    verifier.advance(10, 1, 20, i -> CheckResult.MATCH);
    assertTrue(verifier.canCommit());
  }

  @Test
  void copiesGroupsAndRejectsDuplicateChecks() {
    int[][] groups = {{0}};
    var verifier = new IncrementalVerifier(groups, 0);
    groups[0][0] = 100;
    verifier.advance(1, 0, 20, i -> {
      assertEquals(0, i);
      return CheckResult.MATCH;
    });
    assertTrue(verifier.canCommit());
    assertThrows(IllegalArgumentException.class, () -> new IncrementalVerifier(new int[][]{{0}, {0}}, 0));
  }

  @Test
  void randomizedDifferentialMatchesIndependentFullScan() {
    Random random = new Random(121_100);
    for (int trial = 0; trial < 500; trial++) {
      boolean[] world = new boolean[24];
      for (int i = 0; i < world.length; i++) {
        world[i] = random.nextBoolean();
      }
      int[][] groups = new int[6][4];
      for (int g = 0; g < groups.length; g++) {
        for (int i = 0; i < 4; i++) {
          groups[g][i] = g * 4 + i;
        }
      }
      var incremental = new IncrementalVerifier(groups, 0);
      for (int change = 0; change < 30; change++) {
        int index = random.nextInt(world.length);
        world[index] = random.nextBoolean();
        incremental.markDirty(index / 4, change);
        for (int step = 0; step < 30 && incremental.state() == VerificationState.PENDING; step++) {
          incremental
              .advance(1 + random.nextInt(5), change, 100, i -> world[i] ? CheckResult.MATCH : CheckResult.MISMATCH);
        }
        var full = FullScanReference.scan(world.length, i -> world[i] ? CheckResult.MATCH : CheckResult.MISMATCH);
        assertEquals(full.state(), incremental.state());
        assertEquals(full.state() == VerificationState.CONFIRMED, incremental.canCommit());
      }
    }
  }
}
