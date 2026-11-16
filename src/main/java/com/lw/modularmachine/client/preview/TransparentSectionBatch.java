package com.lw.modularmachine.client.preview;

import com.lw.modularmachine.ModularMachine;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.ApiStatus;
import org.joml.Matrix4f;
/**
 * Owns merged transparency work, uploads and GPU lifetime; source section pages
 * stay with the cache.
 */
@ApiStatus.Internal
final class TransparentSectionBatch implements AutoCloseable {
  private static final int UPLOAD_BYTES = 2 << 20;
  private List<TransparentPage> transparentPages = List.of();
  private CompletableFuture<TransparentQuadOrder.Result> sorting;
  private TransparentQuadOrder.Result pendingOrder;
  private CompletableFuture<List<byte[]>> merging;
  private List<byte[]> mergedVertices;
  private TransparentMesh transparentMesh;
  private int vertexPage, indexCursor;
  private long vertexOffset;
  private Vec3 transparentOrigin = Vec3.ZERO;
  private boolean transparentFailed;
  private Vec3 sortingEye = Vec3.ZERO, sortedEye = new Vec3(Double.MAX_VALUE, 0, 0);

  record TransparentPage(SectionMeshPage page, Vec3 origin) {
  }

  boolean ready() {
    return transparentMesh != null && mergedVertices == null && merging == null && transparentMesh.ready();
  }

  void update(List<TransparentPage> pages, Vec3 eye) {
    if (!pages.equals(transparentPages)) {
      close();
      transparentPages = List.copyOf(pages);
      transparentOrigin = pages.isEmpty() ? Vec3.ZERO : pages.getFirst().origin();
    }
    if (pages.isEmpty() || transparentFailed) {
      return;
    }
    try {
      advanceTransparentOrder(eye);
    } catch (RuntimeException failure) {
      var sources = transparentPages;
      close();
      transparentPages = sources;
      transparentFailed = true;
      ModularMachine.LOGGER.warn("Merged transparent preview failed; using section meshes", failure);
    }
  }

  private void advanceTransparentOrder(Vec3 eye) {
    if (transparentMesh == null && merging == null) {
      // Only detached arrays/coordinates cross the worker boundary. Rebase once
      // per geometry generation; camera movement uploads only global indices.
      var vertices = transparentPages.stream().map(page -> page.page().vertices).toList();
      var offsets = transparentPages.stream().map(page -> page.origin().subtract(transparentOrigin)).toList();
      merging = PreviewMeshData.submitWork(() -> {
        var result = new ArrayList<byte[]>(vertices.size());
        for (int page = 0; page < vertices.size(); page++) {
          var offset = offsets.get(page);
          result.add(TransparentQuadOrder.relocate(vertices.get(page), offset.x, offset.y, offset.z));
        }
        return result;
      });
    }
    if (sorting == null && pendingOrder == null && sortedEye.distanceToSqr(eye) > .25D) {
      var sources = transparentPages.stream()
          .map(
              page -> new TransparentQuadOrder.Source(
                  page.page().centers,
                  page.origin().x,
                  page.origin().y,
                  page.origin().z))
          .toList();
      sortingEye = eye;
      sorting = PreviewMeshData.submitWork(() -> TransparentQuadOrder.sort(sources, eye.x, eye.y, eye.z));
    }
    if (merging != null && merging.isDone()) {
      mergedVertices = merging.join();
      merging = null;
      transparentMesh = new TransparentMesh(mergedVertices.stream().mapToLong(page -> page.length).sum());
    }
    if (sorting != null && sorting.isDone()) {
      pendingOrder = sorting.join();
      sorting = null;
    }
    if (transparentMesh == null) {
      return;
    }
    long deadline = System.nanoTime() + 1_000_000L;
    int uploaded = 0;
    while (mergedVertices != null && uploaded < UPLOAD_BYTES && System.nanoTime() < deadline) {
      var page = mergedVertices.get(vertexPage++);
      transparentMesh.uploadVertices(page, vertexOffset);
      vertexOffset += page.length;
      uploaded += page.length;
      if (vertexPage == mergedVertices.size()) {
        mergedVertices = null;
      }
    }
    while (pendingOrder != null && uploaded < UPLOAD_BYTES && System.nanoTime() < deadline) {
      var indices = pendingOrder.indices();
      int count = Math.min(indices.length - indexCursor, Math.min(65536, (UPLOAD_BYTES - uploaded) / Integer.BYTES));
      if (count == 0) {
        break;
      }
      transparentMesh.uploadIndices(indices, indexCursor, count);
      uploaded += count * Integer.BYTES;
      indexCursor += count;
      if (indexCursor == indices.length) {
        pendingOrder = null;
        indexCursor = 0;
        sortedEye = sortingEye;
      }
    }
  }

  void draw(Matrix4f view, Matrix4f projection, Vec3 translation) {
    var matrix = new Matrix4f(view).translate(
        (float) (transparentOrigin.x - translation.x),
        (float) (transparentOrigin.y - translation.y),
        (float) (transparentOrigin.z - translation.z));
    transparentMesh.draw(matrix, projection);
  }

  @Override
  public void close() {
    if (sorting != null) {
      sorting.cancel(false);
      sorting = null;
    }
    if (merging != null) {
      merging.cancel(false);
      merging = null;
    }
    if (transparentMesh != null) {
      transparentMesh.close();
      transparentMesh = null;
    }
    pendingOrder = null;
    mergedVertices = null;
    vertexPage = indexCursor = 0;
    vertexOffset = 0;
    transparentFailed = false;
    transparentPages = List.of();
    sortedEye = new Vec3(Double.MAX_VALUE, 0, 0);
  }

}
