package com.lw.modularmachine.client.preview;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreviewMeshDataTest {
  private static int[] vertices() {
    var data = new int[32];
    for (int vertex = 0; vertex < 4; vertex++) {
      data[vertex * 8] = Float.floatToRawIntBits(vertex);
      data[vertex * 8 + 1] = Float.floatToRawIntBits(2);
      data[vertex * 8 + 2] = Float.floatToRawIntBits(3);
      data[vertex * 8 + 4] = Float.floatToRawIntBits(.25F);
      data[vertex * 8 + 5] = Float.floatToRawIntBits(.75F);
    }
    return data;
  }

  @Test
  void packingPreservesCoordinatesUvColorAndDetachedVertexOwnership() throws Exception {
    var source = vertices();
    var quad = new PreviewMeshData.Quad(source, 10, -2, 7, 255, 128, 64, 100, true);
    source[0] = Float.floatToRawIntBits(999);
    var pages = PreviewMeshData.submit(List.of(quad)).get(3, TimeUnit.SECONDS);
    assertEquals(1, pages.size());
    var page = pages.getFirst();
    assertTrue(page.translucent());
    var data = page.vertices();
    assertEquals(24, data.length);
    assertEquals(10F, Float.intBitsToFloat(data[0]));
    assertEquals(0F, Float.intBitsToFloat(data[1]));
    assertEquals(10F, Float.intBitsToFloat(data[2]));
    assertEquals(.25F, Float.intBitsToFloat(data[3]));
    assertEquals(.75F, Float.intBitsToFloat(data[4]));
    assertEquals(0x64FF8040, data[5]);
    var upload = ByteBuffer.wrap(page.uploadBytes()).order(ByteOrder.nativeOrder());
    assertEquals(10F, upload.getFloat(0));
    assertEquals(.25F, upload.getFloat(12));
    assertEquals(255, Byte.toUnsignedInt(upload.get(20)));
    assertEquals(128, Byte.toUnsignedInt(upload.get(21)));
    assertEquals(64, Byte.toUnsignedInt(upload.get(22)));
    assertEquals(100, Byte.toUnsignedInt(upload.get(23)));
    assertEquals(data.length * Integer.BYTES, page.uploadBytes().length);
    assertEquals(13F, Float.intBitsToFloat(data[18]));
  }

  @Test
  void complexMeshesArePagedWithoutOld8192QuadTruncation() {
    var quad = new PreviewMeshData.Quad(vertices(), 0, 0, 0, 255, 255, 255, 255, false);
    var quads = new ArrayList<PreviewMeshData.Quad>();
    for (int index = 0; index < 9001; index++) {
      quads.add(quad);
    }
    var pages = PreviewMeshData.pack(quads);
    assertEquals(5, pages.size());
    assertEquals(9001, pages.stream().mapToInt(page -> page.vertices().length / 24).sum());
    assertTrue(pages.stream().allMatch(page -> page.vertices().length <= PreviewMeshData.QUADS_PER_PAGE * 24));
  }

  @Test
  void opaqueAndTranslucentPagesAreIndependentAndEmptyScenesAllocateNoPages() {
    var a = new PreviewMeshData.Quad(vertices(), 0, 0, 0, 255, 255, 255, 255, false);
    var b = new PreviewMeshData.Quad(vertices(), 0, 0, 0, 255, 255, 255, 100, true);
    var pages = PreviewMeshData.pack(List.of(b, a, b));
    assertEquals(2, pages.size());
    assertFalse(pages.getFirst().translucent());
    assertTrue(pages.getLast().translucent());
    assertEquals(48, pages.getLast().vertices().length);
    assertTrue(PreviewMeshData.pack(List.of()).isEmpty());
  }
}
