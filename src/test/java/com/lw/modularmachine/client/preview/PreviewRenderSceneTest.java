package com.lw.modularmachine.client.preview;

import com.lw.modularmachine.network.ProjectionPayloads;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PreviewRenderSceneTest {
  @Test
  void largeSceneKeepsEveryEntryWithBoundedBatchesAndSpatialLookup() {
    var entries = new ArrayList<ProjectionPayloads.Entry>();
    for (int x = 0; x < 48; x++) {
      for (int y = 0; y < 48; y++) {
        for (int z = 0; z < 48; z++) {
          entries.add(new ProjectionPayloads.Entry(new BlockPos(x, y, z), List.of(), List.of()));
        }
      }
    }
    var scene = PreviewRenderScene.create(1, ResourceLocation.parse("minecraft:overworld"), entries);
    assertEquals(entries.size(), scene.sections().stream().mapToInt(section -> section.entries().size()).sum());
    assertTrue(scene.sections().stream().allMatch(section -> section.entries().size() <= 512));
    assertTrue(scene.sections().size() > 128);
    assertEquals(8, scene.atSection(SectionPos.asLong(BlockPos.ZERO)).size());
    assertEquals(8, scene.atSection(SectionPos.asLong(new BlockPos(47, 47, 47))).size());
    assertTrue(scene.animated().isEmpty());
    for (int index : new int[]{0, 1234, entries.size() - 1}) {
      assertSame(entries.get(index), scene.at(entries.get(index).position()));
      assertEquals(index, scene.indexAt(entries.get(index).position()));
    }
  }

  @Test
  void negativeCoordinatesAndBoundsUseActualBatchExtents() {
    var entry = new ProjectionPayloads.Entry(new BlockPos(-17, -1, -33), List.of(), List.of());
    var scene = PreviewRenderScene.create(2, ResourceLocation.parse("minecraft:overworld"), List.of(entry));
    var section = scene.atSection(SectionPos.asLong(entry.position())).getFirst();
    assertEquals(-17D, section.bounds().minX);
    assertEquals(-16D, section.bounds().maxX);
    assertEquals(-33D, section.bounds().minZ);
    assertEquals(-32D, section.bounds().maxZ);
    assertNull(scene.at(BlockPos.ZERO));
    assertEquals(-1, scene.indexAt(BlockPos.ZERO));
  }
}
