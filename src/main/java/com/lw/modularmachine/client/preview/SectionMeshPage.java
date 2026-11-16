package com.lw.modularmachine.client.preview;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.ApiStatus;
import org.lwjgl.system.MemoryUtil;

/**
 * One uploaded section page; its owning mesh closes the GPU buffer on the
 * render thread.
 */
@ApiStatus.Internal
final class SectionMeshPage implements AutoCloseable {
  final VertexBuffer buffer;
  final boolean translucent;
  final MeshData.SortState sorting;
  final float[] centers;
  final int[] vertices;
  Vec3 sortedEye;
  SectionMeshPage(VertexBuffer buffer, boolean translucent, MeshData.SortState sorting, Vec3 sortedEye, int[] vertices) {
    this.buffer = buffer;
    this.translucent = translucent;
    this.sorting = sorting;
    this.sortedEye = sortedEye;
    this.vertices = vertices;
    centers = TransparentQuadOrder.centers(vertices);
  }
  static SectionMeshPage upload(PreviewMeshData.Page page, Vec3 origin, Vec3 eye) {
    try (var bytes = new ByteBufferBuilder(page.uploadBytes().length + 65536)) {
      MemoryUtil.memByteBuffer(bytes.reserve(page.uploadBytes().length), page.uploadBytes().length)
          .put(page.uploadBytes());
      int vertexCount = page.vertices().length / 6;
      var mode = VertexFormat.Mode.QUADS;
      var data = new MeshData(
          bytes.build(),
          new MeshData.DrawState(
              DefaultVertexFormat.POSITION_TEX_COLOR,
              vertexCount,
              mode.indexCount(vertexCount),
              mode,
              VertexFormat.IndexType.least(vertexCount)));
      MeshData.SortState sorting = page.translucent() ? data.sortQuads(
          bytes,
          VertexSorting
              .byDistance((float) (eye.x - origin.x), (float) (eye.y - origin.y), (float) (eye.z - origin.z))) : null;
      var buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
      try {
        buffer.bind();
        buffer.upload(data);
      } catch (RuntimeException failure) {
        buffer.close();
        throw failure;
      } finally {
        VertexBuffer.unbind();
      }
      return new SectionMeshPage(
          buffer,
          page.translucent(),
          sorting,
          eye,
          page.translucent() ? page.vertices() : new int[0]);
    }
  }

  @Override
  public void close() {
    buffer.close();
  }
}
