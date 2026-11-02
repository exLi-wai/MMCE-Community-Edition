package com.lw.modularmachine.machine.structure;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AsyncStructureWorkTest {
  @Test
  void layoutPreparationAndRelocationNeedNoWorldAndPreserveIdentity() throws Exception {
    var json = com.google.gson.JsonParser
        .parseString("{\"schema_version\":2,\"parts\":[{\"offset\":[1,0,0],\"port\":\"energy_input\"}]}")
        .getAsJsonObject();
    var definition = MachineDefinition.parse(
        net.minecraft.resources.ResourceLocation.parse("mmcece:async_unit"),
        json,
        new com.lw.modularmachine.machine.orientation.StateOrientationAdapters.RegistrySnapshot(java.util.Map.of()));
    var origin = new net.minecraft.core.BlockPos(31, 70, -17);
    var orientation = com.lw.modularmachine.machine.orientation.MachineOrientation.IDENTITY;
    var first = AsyncStructureWork.prepare(definition, origin, orientation).get(3, TimeUnit.SECONDS);
    var second = AsyncStructureWork.prepare(definition, origin.above(), orientation).get(3, TimeUnit.SECONDS);
    assertSame(definition, first.definition);
    assertEquals(origin.east(), first.position(0));
    assertEquals(origin.above().east(), second.position(0));
    assertEquals(first.groupFor(first.position(0)), second.groupFor(second.position(0)));
    assertEquals(1, definition.orientationCompilation().cachedOrientationCount());
  }

  @AfterEach
  void stopWorkers() {
    AsyncStructureWork.stop();
  }

  @Test
  void staleResultCannotPublishAfterBreakRepairAba() {
    var verifier = new IncrementalVerifier(new int[][]{{0}, {1}}, 0);
    long old = verifier.revision();
    verifier.markDirty(0, 1);
    verifier.markDirty(0, 2);
    assertFalse(verifier.applySnapshot(old, VerificationFailure.NONE, -1, 3));
    assertFalse(verifier.canCommit());
    assertTrue(verifier.applySnapshot(verifier.revision(), VerificationFailure.NONE, -1, 4));
    assertTrue(verifier.canCommit());
    assertEquals(2, verifier.checkedPredicates());
  }

  @Test
  void partialSnapshotDoesNotEraseExistingMismatch() {
    var verifier = new IncrementalVerifier(new int[][]{{0}, {1}}, 0);
    verifier.advance(8, 0, 20, index -> index == 0 ? CheckResult.MISMATCH : CheckResult.MATCH);
    verifier.markDirty(1, 1);
    assertTrue(verifier.applySnapshot(verifier.revision(), VerificationFailure.NONE, -1, 2));
    assertFalse(verifier.canCommit());
    assertEquals(VerificationFailure.MISMATCH, verifier.failure());
    verifier.markDirty(0, 3);
    verifier.applySnapshot(verifier.revision(), VerificationFailure.NONE, -1, 4);
    assertTrue(verifier.canCommit());
  }

  @Test
  void failedAsyncSnapshotRetainsChecksForRepair() {
    var verifier = new IncrementalVerifier(new int[][]{{0}, {1}}, 0);
    verifier.applySnapshot(verifier.revision(), VerificationFailure.CHUNK_UNAVAILABLE, 1, 1);
    assertFalse(verifier.canCommit());
    verifier.markDirty(1, 2);
    assertArrayEquals(new int[]{0, 1}, verifier.pendingIndices());
    verifier.applySnapshot(verifier.revision(), VerificationFailure.NONE, -1, 3);
    assertTrue(verifier.canCommit());
  }

  @Test
  void pureWorkRunsOnWorkerAndExceptionsPropagate() throws Exception {
    var owner = Thread.currentThread();
    var result = AsyncStructureWork.submit(Thread::currentThread);
    assertNotNull(result);
    var worker = result.get(3, TimeUnit.SECONDS);
    assertNotSame(owner, worker);
    assertTrue(worker.getName().startsWith("mmcece-structure"));
    var failed = AsyncStructureWork.submit(() -> {
      throw new IllegalStateException("predicate failed");
    });
    assertNotNull(failed);
    assertThrows(java.util.concurrent.ExecutionException.class, () -> failed.get(3, TimeUnit.SECONDS));
  }

  @Test
  void saturatedQueueRejectsWithoutCallerThreadFallbackAndStopResolvesQueuedFutures() throws Exception {
    var entered = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    var pending = new ArrayList<CompletableFuture<Integer>>();
    for (int index = 0; index < 18; index++) {
      var future = AsyncStructureWork.submit(() -> {
        entered.countDown();
        try {
          release.await();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
        return 1;
      });
      assertNotNull(future);
      pending.add(future);
    }
    assertTrue(entered.await(3, TimeUnit.SECONDS));
    assertNull(AsyncStructureWork.submit(() -> fail("Ran rejected task")));
    AsyncStructureWork.stop();
    assertTrue(pending.stream().allMatch(CompletableFuture::isDone));
    release.countDown();
    assertEquals(2, AsyncStructureWork.submit(() -> 2).get(3, TimeUnit.SECONDS));
  }

  @Test
  void snapshotPermitsAreBoundedAndDoubleReleaseIsHarmless() {
    var permits = new ArrayList<AsyncStructureWork.Permit>();
    try {
      for (int index = 0; index < 8; index++) {
        var permit = AsyncStructureWork.acquireSnapshot();
        assertNotNull(permit);
        permits.add(permit);
      }
      assertNull(AsyncStructureWork.acquireSnapshot());
    } finally {
      permits.forEach(permit -> {
        permit.close();
        permit.close();
      });
    }
    try (var permit = AsyncStructureWork.acquireSnapshot()) {
      assertNotNull(permit);
    }
  }
}
