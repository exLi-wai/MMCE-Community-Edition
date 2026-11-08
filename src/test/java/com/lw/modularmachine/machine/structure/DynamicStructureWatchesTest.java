package com.lw.modularmachine.machine.structure;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DynamicStructureWatchesTest {
  @Test
  void slicedRetirementPreservesSharedPositionsAndContentDependenciesOfOtherSegments() {
    var watches = new DynamicStructureWatches();
    var shared = new BlockPos(1, 40, 1);
    var boundary = new BlockPos(32, 40, 1);
    watches.add(0, shared, false);
    watches.add(63, shared, true);
    watches.add(0, boundary, false);
    assertEquals(1L | Long.MIN_VALUE, watches.affected(shared, false));
    assertEquals(Long.MIN_VALUE, watches.affected(shared, true));
    assertEquals(0, watches.affected(boundary, true));
    var removedSections = new HashSet<Long>();
    var removedChunks = new HashSet<Long>();
    watches.invalidate(1L);
    while (!watches.ready()) {
      assertEquals(1, watches.advanceCleanup(1, removedSections::add, removedChunks::add));
    }
    assertEquals(Long.MIN_VALUE, watches.affected(shared, false));
    assertEquals(Long.MIN_VALUE, watches.affected(shared, true));
    assertEquals(Long.MIN_VALUE, watches.affectedChunk(ChunkPos.asLong(0, 0)));
    assertEquals(0, watches.affected(boundary, false));
    assertEquals(Set.of(SectionPos.asLong(boundary)), removedSections);
    assertEquals(Set.of(ChunkPos.asLong(2, 0)), removedChunks);
    watches.invalidate(Long.MIN_VALUE);
    while (!watches.ready()) {
      watches.advanceCleanup(1, removedSections::add, removedChunks::add);
    }
    assertTrue(watches.sections().isEmpty());
    assertTrue(watches.chunks().isEmpty());
    assertEquals(0, watches.affected(shared, true));
  }
}
