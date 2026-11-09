package com.lw.modularmachine.client.preview;

import java.util.List;
import java.util.ArrayList;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransparentQuadOrderTest {
  @Test
  void interleavingPagesAreDrawnInGlobalOrderRatherThanPageOrder() {
    var sources = List.of(
        new TransparentQuadOrder.Source(new float[]{1, 0, 0, 9, 0, 0}, 0, 0, 0),
        new TransparentQuadOrder.Source(new float[]{5, 0, 0}, 0, 0, 0));
    var result = TransparentQuadOrder.sort(sources, 0, 0, 0);
    assertArrayEquals(new int[]{4, 5, 6, 6, 7, 4, 8, 9, 10, 10, 11, 8, 0, 1, 2, 2, 3, 0}, result.indices());
  }

  @Test
  void sectionOriginsAndEyeMovementChangeOnlyTheIndexOrder() {
    var sources = List.of(
        new TransparentQuadOrder.Source(new float[]{0, 0, 0}, -16, 0, 0),
        new TransparentQuadOrder.Source(new float[]{0, 0, 0}, 16, 0, 0));
    assertEquals(4, TransparentQuadOrder.sort(sources, -10, 0, 0).indices()[0]);
    assertEquals(0, TransparentQuadOrder.sort(sources, 10, 0, 0).indices()[0]);
    assertEquals(12, TransparentQuadOrder.sort(sources, 0, 0, 0).indices().length);
    assertEquals(0, TransparentQuadOrder.sort(List.of(), 0, 0, 0).indices().length);
  }

  @Test
  void interleavedLargePagesProduceOneCompleteGlobalIndexBuffer() {
    int pages = 128, quads = 2048;
    var sources = new ArrayList<TransparentQuadOrder.Source>();
    for (int page = 0; page < pages; page++) {
      var centers = new float[quads * 3];
      for (int quad = 0; quad < quads; quad++) {
        centers[quad * 3] = quad * pages + page;
      }
      sources.add(new TransparentQuadOrder.Source(centers, 0, 0, 0));
    }
    var indices = TransparentQuadOrder.sort(sources, -1, 0, 0).indices();
    assertEquals(pages * quads * 6, indices.length);
    var seen = new boolean[pages * quads];
    int previous = Integer.MAX_VALUE;
    for (int offset = 0; offset < indices.length; offset += 6) {
      int quad = indices[offset] / 4;
      assertFalse(seen[quad]);
      seen[quad] = true;
      int distance = quad % quads * pages + quad / quads;
      assertTrue(distance < previous);
      previous = distance;
      assertEquals(indices[offset], indices[offset + 5]);
      assertEquals(indices[offset] + 3, indices[offset + 4]);
    }
  }

  @Test
  void mergingRebasesPositionsWithoutChangingUvColorOrSourceVertices() {
    var vertices = new int[]{Float.floatToRawIntBits(.25F), Float.floatToRawIntBits(.5F), Float.floatToRawIntBits(.75F),
        Float.floatToRawIntBits(.125F), Float.floatToRawIntBits(.875F), 0x64336699};
    var bytes = TransparentQuadOrder.relocate(vertices, 16, -32, 48);
    var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
    assertEquals(16.25F, buffer.getFloat(0));
    assertEquals(-31.5F, buffer.getFloat(4));
    assertEquals(48.75F, buffer.getFloat(8));
    assertEquals(.125F, buffer.getFloat(12));
    assertEquals(.875F, buffer.getFloat(16));
    assertArrayEquals(new byte[]{0x33, 0x66, (byte) 0x99, 0x64}, java.util.Arrays.copyOfRange(bytes, 20, 24));
    assertEquals(.25F, Float.intBitsToFloat(vertices[0]));
  }
}
