package com.lw.modularmachine.machine.structure;

import com.google.gson.JsonParser;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.orientation.StateOrientationAdapters.RegistrySnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AsyncStructureWorkTest {
  @Test
  void dynamicTemplatesShareCompilationButIsolateLengthsOrientationAndDefinitionEpoch() throws Exception {
    var definition = dynamicDefinition();
    var lengths = new HashMap<>(Map.of("line", 2));
    var firstFuture = AsyncStructureWork.prepare(definition, lengths, BlockPos.ZERO, MachineOrientation.IDENTITY);
    lengths.put("line", 1);
    var secondFuture = AsyncStructureWork
        .prepare(definition, Map.of("line", 2), new BlockPos(31, 40, -17), MachineOrientation.IDENTITY);
    var first = firstFuture.get(3, TimeUnit.SECONDS);
    var second = secondFuture.get(3, TimeUnit.SECONDS);
    assertSame(first.definition, second.definition, "Concurrent requests share the immutable expanded definition");
    assertEquals(Map.of("line", 2), first.definition.dynamicLengths());
    assertEquals(first.position(1).offset(second.origin), second.position(1));
    var shorter = AsyncStructureWork.prepare(definition, lengths, BlockPos.ZERO, MachineOrientation.IDENTITY)
        .get(3, TimeUnit.SECONDS);
    assertNotSame(first.definition, shorter.definition);
    assertEquals(Map.of("line", 1), shorter.definition.dynamicLengths());
    var rotated = AsyncStructureWork
        .prepare(definition, Map.of("line", 2), BlockPos.ZERO, MachineOrientation.of(Direction.EAST, 0))
        .get(3, TimeUnit.SECONDS);
    assertNotEquals(first.position(1), rotated.position(1));
    AsyncStructureWork.clearTemplates();
    var fresh = AsyncStructureWork.prepare(definition, Map.of("line", 2), BlockPos.ZERO, MachineOrientation.IDENTITY)
        .get(3, TimeUnit.SECONDS);
    assertNotSame(first.definition, fresh.definition);
    assertSame(definition, fresh.definition.sourceDefinition());
  }

  @Test
  void externalLengthInvalidationRevokesOldSnapshotsWithoutDirtyingUnchangedGroups() {
    var verifier = new IncrementalVerifier(new int[][]{{0}, {1}}, 0);
    assertTrue(verifier.applySnapshot(verifier.revision(), VerificationFailure.NONE, -1, 0));
    verifier.markDirty(1, 1);
    long stale = verifier.revision();
    verifier.invalidateDependency(2);
    assertFalse(verifier.canCommit());
    assertFalse(verifier.applySnapshot(stale, VerificationFailure.NONE, -1, 3));
    assertArrayEquals(new int[]{1}, verifier.pendingIndices());
    assertTrue(verifier.applySnapshot(verifier.revision(), VerificationFailure.NONE, -1, 4));
    assertTrue(verifier.canCommit());
    verifier.invalidateDependency(5);
    assertEquals(0, verifier.pendingIndices().length);
    assertFalse(verifier.canCommit());
  }

  private static MachineDefinition dynamicDefinition() {
    var json = JsonParser.parseString("""
        {"orientation":{"policy":"all_24"},"parts":[{"offset":[2,0,0],"block":"energy_input"}],
        "dynamic_patterns":[{"id":"line","minimum":0,"maximum":3,
          "start_offset":[0,0,1],"step":[0,0,1],
          "parts":[{"offset":[0,0,0],"block":"item_input"}]}]}
        """).getAsJsonObject();
    return MachineDefinition
        .parse(ResourceLocation.parse("mmcece:dynamic_cache"), json, new RegistrySnapshot(Map.of()));
  }

  @Test
  void layoutPreparationAndRelocationNeedNoWorldAndPreserveIdentity() throws Exception {
    var json = JsonParser.parseString("{\"parts\":[{\"offset\":[1,0,0],\"block\":\"energy_input\"}]}")
        .getAsJsonObject();
    var definition = MachineDefinition
        .parse(ResourceLocation.parse("mmcece:async_unit"), json, new RegistrySnapshot(Map.of()));
    var origin = new BlockPos(31, 70, -17);
    var orientation = MachineOrientation.IDENTITY;
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
    assertThrows(ExecutionException.class, () -> failed.get(3, TimeUnit.SECONDS));
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
