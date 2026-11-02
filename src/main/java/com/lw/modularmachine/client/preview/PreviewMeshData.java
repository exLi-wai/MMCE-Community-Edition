package com.lw.modularmachine.client.preview;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * CPU-only packing of detached quad data. No models, worlds, native buffers or
 * GL.
 */
final class PreviewMeshData {
  static final int QUADS_PER_PAGE = 2048;
  private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(
      1,
      2,
      30,
      TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(8),
      runnable -> {
        var thread = new Thread(runnable, "mmcece-preview-pack");
        thread.setDaemon(true);
        return thread;
      });
  static {
    WORKERS.allowCoreThreadTimeOut(true);
  }

  record Quad(
      int[] vertices,
      float x,
      float y,
      float z,
      float red,
      float green,
      float blue,
      int alpha,
      boolean translucent) {
    Quad {
      vertices = vertices.clone();
    }
  }
  record Page(int[] vertices, boolean translucent, byte[] uploadBytes) {
    Page(int[] vertices, boolean translucent) {
      this(vertices, translucent, encodeUpload(vertices));
    }
  }

  private static byte[] encodeUpload(int[] vertices) {
    var data = ByteBuffer.allocate(vertices.length * Integer.BYTES).order(ByteOrder.nativeOrder());
    data.asIntBuffer().put(vertices);
    for (int index = 5; index < vertices.length; index += 6) {
      int color = vertices[index];
      int offset = index * Integer.BYTES;
      data.put(offset, (byte) (color >> 16));
      data.put(offset + 1, (byte) (color >> 8));
      data.put(offset + 2, (byte) color);
      data.put(offset + 3, (byte) (color >>> 24));
    }
    return data.array();
  }

  private PreviewMeshData() {
  }

  static CompletableFuture<List<Page>> submit(List<Quad> quads) {
    var detached = List.copyOf(quads);
    return submitWork(() -> pack(detached));
  }

  /** Pure scene indexing shares the bounded queue; never capture a live level. */
  static <T> CompletableFuture<T> submitWork(Supplier<T> work) {
    var future = new CompletableFuture<T>();
    try {
      WORKERS.execute(() -> {
        try {
          future.complete(work.get());
        } catch (Throwable failure) {
          future.completeExceptionally(failure);
        }
      });
    } catch (RejectedExecutionException busy) {
      return null;
    }
    return future;
  }

  static List<Page> pack(List<Quad> quads) {
    var pages = new ArrayList<Page>();
    for (boolean transparent : new boolean[]{false, true}) {
      int[] data = new int[QUADS_PER_PAGE * 4 * 6];
      int cursor = 0;
      for (var quad : quads) {
        if (quad.translucent() != transparent) {
          continue;
        }
        if (cursor == data.length) {
          pages.add(new Page(data, transparent));
          data = new int[data.length];
          cursor = 0;
        }
        int stride = quad.vertices().length / 4;
        for (int vertex = 0; vertex < 4; vertex++) {
          int index = vertex * stride;
          data[cursor++] = Float.floatToRawIntBits(Float.intBitsToFloat(quad.vertices()[index]) + quad.x());
          data[cursor++] = Float.floatToRawIntBits(Float.intBitsToFloat(quad.vertices()[index + 1]) + quad.y());
          data[cursor++] = Float.floatToRawIntBits(Float.intBitsToFloat(quad.vertices()[index + 2]) + quad.z());
          data[cursor++] = quad.vertices()[index + 4];
          data[cursor++] = quad.vertices()[index + 5];
          data[cursor++] = quad.alpha() << 24 | channel(quad.red()) << 16 | channel(quad.green()) << 8 | channel(
              quad.blue());
        }
      }
      if (cursor > 0) {
        pages.add(new Page(java.util.Arrays.copyOf(data, cursor), transparent));
      }
    }
    return List.copyOf(pages);
  }

  private static int channel(float value) {
    return Math.max(0, Math.min(255, Math.round(value)));
  }
}
