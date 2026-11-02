package com.lw.modularmachine.network;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProjectionTransferTest {
  @Test
  void ordinaryLargeProjectionDoesNotRequireThousandsOfTinyPackets() {
    var entries = new ArrayList<ProjectionPayloads.Entry>();
    for (int index = 0; index < 101_732; index++) {
      entries.add(new ProjectionPayloads.Entry(new BlockPos(index, 64, 0), List.of(), List.of()));
    }
    int cursor = 0, packets = 0;
    while (cursor < entries.size()) {
      int end = ProjectionPayloads.chunkEnd(entries, cursor);
      assertTrue(end > cursor && end - cursor <= 256);
      new ProjectionPayloads.Snapshot(
          1,
          1,
          ResourceLocation.parse("minecraft:overworld"),
          ResourceLocation.parse("mmcece:test"),
          BlockPos.ZERO,
          cursor,
          entries.size(),
          entries.subList(cursor, end));
      cursor = end;
      packets++;
    }
    assertEquals(398, packets);
    assertEquals(entries.size(), cursor);
  }

  @Test
  void complexEntriesReducePacketCountByByteBudgetAndAlwaysMakeProgress() {
    var names = java.util.stream.IntStream.range(0, 64).mapToObj(index -> "property_" + index + "_".repeat(48))
        .toList();
    var entry = new ProjectionPayloads.Entry(BlockPos.ZERO, List.of(), names);
    var entries = java.util.Collections.nCopies(256, entry);
    int end = ProjectionPayloads.chunkEnd(entries, 0);
    assertTrue(end > 0 && end < 256, "Complex rows ignored the payload byte target");
    assertEquals(256, ProjectionPayloads.chunkEnd(entries, 255));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProjectionPayloads.Snapshot(
            1,
            1,
            ResourceLocation.parse("minecraft:overworld"),
            ResourceLocation.parse("mmcece:test"),
            BlockPos.ZERO,
            0,
            257,
            java.util.Collections.nCopies(257, entry)));
  }
}
