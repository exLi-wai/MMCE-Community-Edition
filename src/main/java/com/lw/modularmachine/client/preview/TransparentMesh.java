package com.lw.modularmachine.client.preview;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.system.MemoryUtil;

/**
 * Internal render-thread merged geometry with atomically replaced global
 * indices.
 */
final class TransparentMesh implements AutoCloseable {
  private int array = -1, vertices = -1, indices = -1, pendingIndices = -1;
  private int indexCount;

  TransparentMesh(long bytes) {
    RenderSystem.assertOnRenderThread();
    try {
      array = GlStateManager._glGenVertexArrays();
      vertices = GlStateManager._glGenBuffers();
      bind();
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertices);
      GL15.glBufferData(GL15.GL_ARRAY_BUFFER, bytes, GL15.GL_STATIC_DRAW);
      DefaultVertexFormat.POSITION_TEX_COLOR.setupBufferState();
    } catch (RuntimeException failure) {
      close();
      throw failure;
    } finally {
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
      VertexBuffer.unbind();
    }
  }

  void uploadVertices(byte[] page, long offset) {
    RenderSystem.assertOnRenderThread();
    var data = MemoryUtil.memAlloc(page.length);
    try {
      data.put(page).flip();
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertices);
      GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, offset, data);
    } finally {
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
      MemoryUtil.memFree(data);
    }
  }

  void uploadIndices(int[] order, int offset, int count) {
    RenderSystem.assertOnRenderThread();
    var data = MemoryUtil.memAllocInt(count);
    try {
      bind();
      if (offset == 0) {
        pendingIndices = GlStateManager._glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, pendingIndices);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, (long) order.length * Integer.BYTES, GL15.GL_STATIC_DRAW);
      } else {
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, pendingIndices);
      }
      data.put(order, offset, count).flip();
      GL15.glBufferSubData(GL15.GL_ELEMENT_ARRAY_BUFFER, (long) offset * Integer.BYTES, data);
      if (offset + count == order.length) {
        if (indices >= 0) {
          RenderSystem.glDeleteBuffers(indices);
        }
        indices = pendingIndices;
        pendingIndices = -1;
        indexCount = order.length;
      }
    } finally {
      VertexBuffer.unbind();
      MemoryUtil.memFree(data);
    }
  }

  boolean ready() {
    return indices >= 0;
  }

  private void bind() {
    BufferUploader.invalidate();
    GlStateManager._glBindVertexArray(array);
  }

  void draw(Matrix4f view, Matrix4f projection) {
    RenderSystem.assertOnRenderThread();
    var shader = GameRenderer.getPositionTexColorShader();
    try {
      bind();
      GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indices);
      shader.setDefaultUniforms(VertexFormat.Mode.QUADS, view, projection, Minecraft.getInstance().getWindow());
      shader.apply();
      GL11.glDrawElements(GL11.GL_TRIANGLES, indexCount, GL11.GL_UNSIGNED_INT, 0L);
    } finally {
      shader.clear();
      VertexBuffer.unbind();
    }
  }

  @Override
  public void close() {
    RenderSystem.assertOnRenderThread();
    if (vertices >= 0) {
      RenderSystem.glDeleteBuffers(vertices);
      vertices = -1;
    }
    if (indices >= 0) {
      RenderSystem.glDeleteBuffers(indices);
      indices = -1;
    }
    if (pendingIndices >= 0) {
      RenderSystem.glDeleteBuffers(pendingIndices);
      pendingIndices = -1;
    }
    if (array >= 0) {
      RenderSystem.glDeleteVertexArrays(array);
      array = -1;
    }
  }
}
