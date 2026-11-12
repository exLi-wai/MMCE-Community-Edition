package com.lw.modularmachine.client.preview;

import com.lw.modularmachine.api.client.preview.PreviewSceneData;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreviewRenderSceneTest {
  @Test
  void publicSceneInputIsDetachedAndComputesBounds() {
    var originalEntries = new ArrayList<PreviewSceneData.Entry>();
    var entry = new PreviewSceneData.Entry(new BlockPos(-2, 3, 7), List.of(), new ArrayList<>(List.of("facing")));
    originalEntries.add(entry);

    var data = new PreviewSceneData(0xFF123456, originalEntries);
    originalEntries.clear();

    assertEquals(0x123456, data.machineColor());
    assertEquals(List.of(entry), data.entries());
    assertEquals(-2D, data.bounds().minX);
    assertEquals(8D, data.bounds().maxZ);
    assertEquals(1F, data.extent());
    assertEquals(List.of("facing"), data.entries().getFirst().properties());
    assertThrows(
        UnsupportedOperationException.class,
        () -> data.entries().add(new PreviewSceneData.Entry(BlockPos.ZERO, List.of(), List.of())));
  }

  @Test
  void emptySceneHasUnitBounds() {
    var data = new PreviewSceneData(0, List.of());

    assertEquals(1D, data.bounds().getXsize());
    assertEquals(1D, data.bounds().getYsize());
    assertEquals(1D, data.bounds().getZsize());
    assertEquals(1F, data.extent());
  }

  @Test
  void largePlanarScenesBuildAcrossAllAxesWithoutLosingLookupOrder() {
    for (int plane = 0; plane < 3; plane++) {
      var entries = new ArrayList<PreviewSceneData.Entry>();
      for (int u = -198; u <= 213; u++) {
        for (int v = 1; v <= 247; v++) {
          var pos = switch (plane) {
            case 0 -> new BlockPos(6568 + u, 98, 10003 + v);
            case 1 -> new BlockPos(6568 + u, 98 + v, 10003);
            default -> new BlockPos(6568, 98 + u, 10003 + v);
          };
          entries.add(new PreviewSceneData.Entry(pos, List.of(), List.of()));
        }
      }
      long started = System.nanoTime();
      var scene = PreviewRenderScene.create(new PreviewSceneData(0xFFFFFF, entries));
      double elapsedMs = (System.nanoTime() - started) / 1_000_000D;
      System.out.printf("Planar scene axis=%d entries=%d buildMs=%.3f%n", plane, entries.size(), elapsedMs);
      assertEquals(entries.size(), scene.sections().stream().mapToInt(section -> section.entries().size()).sum());
      for (int index = 0; index < entries.size(); index++) {
        assertSame(entries.get(index), scene.at(entries.get(index).position()));
        assertEquals(index, scene.indexAt(entries.get(index).position()));
      }
    }
  }

  @Test
  void duplicatePositionsKeepFirstSelectionIndexAndLastEntry() {
    var first = new PreviewSceneData.Entry(BlockPos.ZERO, List.of(), List.of());
    var second = new PreviewSceneData.Entry(new BlockPos(-17, 98, 10003), List.of(), List.of());
    var replacement = new PreviewSceneData.Entry(BlockPos.ZERO, List.of(), List.of());
    var scene = PreviewRenderScene.create(new PreviewSceneData(0xFFFFFF, List.of(first, second, replacement)));
    assertSame(replacement, scene.at(BlockPos.ZERO));
    assertEquals(0, scene.indexAt(BlockPos.ZERO));
    assertEquals(1, scene.indexAt(second.position()));
    assertEquals(3, scene.sections().stream().mapToInt(section -> section.entries().size()).sum());
  }

  @Test
  void largeSceneKeepsEveryEntryWithBoundedBatchesAndSpatialLookup() {
    var entries = new ArrayList<PreviewSceneData.Entry>();
    for (int x = 0; x < 48; x++) {
      for (int y = 0; y < 48; y++) {
        for (int z = 0; z < 48; z++) {
          entries.add(new PreviewSceneData.Entry(new BlockPos(x, y, z), List.of(), List.of()));
        }
      }
    }
    var scene = PreviewRenderScene.create(new PreviewSceneData(0xFFFFFF, entries));
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
    var entry = new PreviewSceneData.Entry(new BlockPos(-17, -1, -33), List.of(), List.of());
    var scene = PreviewRenderScene.create(new PreviewSceneData(0xFFFFFF, List.of(entry)));
    var section = scene.atSection(SectionPos.asLong(entry.position())).getFirst();
    assertEquals(-17D, section.bounds().minX);
    assertEquals(-16D, section.bounds().maxX);
    assertEquals(-33D, section.bounds().minZ);
    assertEquals(-32D, section.bounds().maxZ);
    assertNull(scene.at(BlockPos.ZERO));
    assertEquals(-1, scene.indexAt(BlockPos.ZERO));
  }
}
