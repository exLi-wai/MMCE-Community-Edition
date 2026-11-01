package com.lw.modularmachine.client.preview;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * Bounded render-thread GPU cache. Refresh one section/tick; upload at most two
 * sections/frame.
 */
final class SectionMeshCache implements AutoCloseable {
  private static final float[] FACE_SHADE = {.5F, 1F, .8F, .8F, .6F, .6F};
  private record Mesh(VertexBuffer buffer, Vec3 origin) {
  }
  private final Map<Long, Mesh> meshes = new LinkedHashMap<>();
  private final ArrayDeque<PreviewRenderScene.Section> dirty = new ArrayDeque<>();
  private final Map<Long, List<BlockState>> observed = new HashMap<>();
  private PreviewRenderScene scene;
  private boolean preview;
  private int previewLayer = Integer.MIN_VALUE;
  private int previewStep;
  private int inspect;
  void setScene(PreviewRenderScene value) {
    close();
    scene = value;
    dirty.addAll(value.sections());
  }
  void tick() {
    if (scene == null || scene.sections().isEmpty()) {
      return;
    }
    var section = scene.sections().get(Math.floorMod(inspect++, scene.sections().size()));
    if (!dirty.contains(section)) {
      dirty.addLast(section);
    }
  }
  void setPreviewScene(PreviewRenderScene value) {
    setScene(value);
    preview = true;
  }
  void setPreviewState(int layer, int step) {
    if (previewLayer == layer && previewStep == step) {
      return;
    }
    previewLayer = layer;
    previewStep = step;
    for (var mesh : meshes.values()) {
      if (mesh.buffer() != null) {
        mesh.buffer().close();
      }
    }
    meshes.clear();
    observed.clear();
    dirty.clear();
    if (scene != null) {
      dirty.addAll(scene.sections());
    }
  }
  void upload() {
    long deadline = System.nanoTime() + 2_000_000L;
    for (int work = 0; work < 2 && !dirty.isEmpty() && System.nanoTime() < deadline; work++) {
      var section = dirty.removeFirst();
      var level = Minecraft.getInstance().level;
      var states = section.entries().stream()
          .map(
              entry -> preview ? entry.states().isEmpty() ? null : entry.states()
                  .get(Math.floorMod(previewStep, entry.states().size())) : level
                      .hasChunkAt(entry.position()) ? level.getBlockState(entry.position()) : null)
          .toList();
      if (meshes.containsKey(section.key()) && states.equals(observed.get(section.key()))) {
        continue;
      }
      var mesh = compile(section);
      observed.put(section.key(), states);
      var old = meshes.put(section.key(), mesh);
      if (old != null && old.buffer() != null) {
        old.buffer().close();
      }
      while (meshes.size() > 128) {
        var first = meshes.entrySet().iterator();
        var entry = first.next();
        if (entry.getValue().buffer() != null) {
          entry.getValue().buffer().close();
        }
        first.remove();
      }
    }
  }
  private Mesh compile(PreviewRenderScene.Section section) {
    var mc = Minecraft.getInstance();
    var origin = new Vec3(section.bounds().minX, section.bounds().minY, section.bounds().minZ);
    try (var bytes = new ByteBufferBuilder(65536)) {
      var builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
      int quads = 0;
      for (var entry : section.entries()) {
        if (entry.states().isEmpty()
            || !preview && !mc.level.hasChunkAt(entry.position())
            || preview && previewLayer != Integer.MIN_VALUE && entry.position().getY() != previewLayer) {
          continue;
        }
        var actual = preview ? Blocks.AIR.defaultBlockState() : mc.level.getBlockState(entry.position());
        if (!preview && entry.matches(actual)) {
          continue;
        }
        var state = entry.states().get(Math.floorMod(previewStep, entry.states().size()));
        if (state.getRenderShape() != RenderShape.MODEL) {
          continue;
        }
        var model = mc.getBlockRenderer().getBlockModel(state);
        for (int side = -1; side < 6; side++) {
          Direction face = side < 0 ? null : Direction.values()[side];
          for (var quad : model.getQuads(state, face, RandomSource.create(42))) {
            if (++quads > 8192) {
              continue;
            }
            int[] vertices = quad.getVertices();
            int stride = vertices.length / 4;
            int tint = quad.isTinted() ? mc.getBlockColors()
                .getColor(state, mc.level, entry.position(), quad.getTintIndex()) : 0xFFFFFF;
            float red = preview ? 1F : actual.isAir() ? .45F : 1F, green = preview ? 1F : actual.isAir() ? .85F : .25F,
                blue = preview ? 1F : actual.isAir() ? 1F : .25F;
            float shade = side < 0 ? 1F : FACE_SHADE[side];
            for (int vertex = 0; vertex < 4; vertex++) {
              int index = vertex * stride;
              float x = Float.intBitsToFloat(vertices[index]) + (float) (entry.position().getX() - origin.x);
              float y = Float.intBitsToFloat(vertices[index + 1]) + (float) (entry.position().getY() - origin.y);
              float z = Float.intBitsToFloat(vertices[index + 2]) + (float) (entry.position().getZ() - origin.z);
              builder.addVertex(x, y, z)
                  .setUv(Float.intBitsToFloat(vertices[index + 4]), Float.intBitsToFloat(vertices[index + 5])).setColor(
                      (int) ((tint >> 16 & 255) * red * shade),
                      (int) ((tint >> 8 & 255) * green * shade),
                      (int) ((tint & 255) * blue * shade),
                      preview ? 255 : 100);
            }
          }
        }
      }
      var data = builder.build();
      if (data == null) {
        return new Mesh(null, origin);
      }
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
      return new Mesh(buffer, origin);
    }
  }
  void draw(RenderLevelStageEvent event) {
    if (scene == null) {
      return;
    }
    RenderSystem.enableBlend();
    RenderSystem.defaultBlendFunc();
    RenderSystem.enableDepthTest();
    RenderSystem.depthMask(false);
    RenderSystem.disableCull();
    RenderSystem.setShaderColor(1, 1, 1, 1);
    RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
    RenderSystem.setShaderTexture(0, InventoryMenu.BLOCK_ATLAS);
    try {
      var camera = event.getCamera().getPosition();
      for (var section : scene.sections()) {
        if (!event.getFrustum().isVisible(section.bounds()) || section.bounds().distanceToSqr(camera) > 128 * 128) {
          continue;
        }
        var mesh = meshes.get(section.key());
        if (mesh == null || mesh.buffer() == null) {
          continue;
        }
        var matrix = new Matrix4f(event.getModelViewMatrix()).translate(
            (float) (mesh.origin().x - camera.x),
            (float) (mesh.origin().y - camera.y),
            (float) (mesh.origin().z - camera.z));
        mesh.buffer().bind();
        mesh.buffer().drawWithShader(matrix, event.getProjectionMatrix(), GameRenderer.getPositionTexColorShader());
      }
    } finally {
      VertexBuffer.unbind();
      RenderSystem.depthMask(true);
      RenderSystem.enableCull();
      RenderSystem.disableBlend();
    }
  }
  @Override
  public void close() {
    for (var mesh : meshes.values()) {
      if (mesh.buffer() != null) {
        mesh.buffer().close();
      }
    }
    meshes.clear();
    observed.clear();
    dirty.clear();
    scene = null;
    inspect = 0;
    preview = false;
    previewLayer = Integer.MIN_VALUE;
    previewStep = 0;
  }
  void drawPreview(Matrix4f view, Matrix4f projection) {
    if (scene == null) {
      return;
    }
    RenderSystem.enableBlend();
    RenderSystem.defaultBlendFunc();
    RenderSystem.enableDepthTest();
    RenderSystem.depthMask(true);
    RenderSystem.disableCull();
    RenderSystem.setShaderColor(1, 1, 1, 1);
    RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
    RenderSystem.setShaderTexture(0, InventoryMenu.BLOCK_ATLAS);
    RenderSystem.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
    try {
      for (var mesh : meshes.values()) {
        if (mesh.buffer() == null) {
          continue;
        }
        var matrix = new Matrix4f(view)
            .translate((float) mesh.origin().x, (float) mesh.origin().y, (float) mesh.origin().z);
        mesh.buffer().bind();
        mesh.buffer().drawWithShader(matrix, projection, GameRenderer.getPositionTexColorShader());
      }
    } finally {
      VertexBuffer.unbind();
      RenderSystem.depthMask(true);
      RenderSystem.enableCull();
      RenderSystem.disableBlend();
    }
  }
}
