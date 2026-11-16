package com.lw.modularmachine.client.preview;

import com.lw.modularmachine.api.client.preview.PreviewSceneData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;

/**
 * Render-thread block and fluid model capture. Workers only receive detached
 * quads.
 */
@ApiStatus.Internal
final class PreviewModelCapture {
  private static final float[] FACE_SHADE = {.5F, 1F, .8F, .8F, .6F, .6F};
  private static final Direction[] FACES = Direction.values();

  private PreviewModelCapture() {
  }

  static void capture(SectionMeshBuild job, int index, boolean preview, int previewLayer, int previewStep, PreviewBlockView blockView) {
    var entry = job.section.entries().get(index);
    var captured = job.states.get(index);
    // Schematic data is already available even when the real chunk is not.
    // Unknown world positions remain visible as missing, without loading chunks.
    var actual = captured == null ? Blocks.AIR.defaultBlockState() : captured;
    var state = entry.states().isEmpty() ? null : entry.states().get(Math.floorMod(previewStep, entry.states().size()));
    if (state == null
        || state.getRenderShape() != RenderShape.MODEL && !(state.getBlock() instanceof LiquidBlock)
        || !preview && captured != null && entry.matches(actual)
        || preview && previewLayer != Integer.MIN_VALUE && entry.position().getY() != previewLayer) {
      return;
    }
    var mc = Minecraft.getInstance();
    var view = blockView;
    if (state.getBlock() instanceof LiquidBlock) {
      job.modelSensitive = true;
      captureLiquid(job, entry, state, actual, view, mc, preview);
      return;
    }
    var model = mc.getBlockRenderer().getBlockModel(state);
    var seedData = view.modelData(entry.position(), state);
    var modelData = model.getModelData(view, entry.position(), state, seedData);
    job.modelSensitive |= state.hasBlockEntity() || !modelData.getProperties().isEmpty();
    var random = job.random;
    long seed = state.getSeed(entry.position());
    random.setSeed(seed);
    var types = model.getRenderTypes(state, random, modelData);
    for (var type : types) {
      boolean translucent = !preview || type == RenderType.translucent();
      for (int side = -1; side < 6; side++) {
        var face = side < 0 ? null : FACES[side];
        // Ghost interiors remain visible; opaque blueprint neighbors use the
        // same face-occlusion rules as vanilla, including layer cut surfaces.
        if (preview
            && face != null
            && !Block.shouldRenderFace(state, view, entry.position(), face, entry.position().relative(face))) {
          continue;
        }
        random.setSeed(seed);
        for (var quad : model.getQuads(state, face, random, modelData, type)) {
          int tint = quad.isTinted() ? mc.getBlockColors()
              .getColor(state, view, entry.position(), quad.getTintIndex()) : 0xFFFFFF;
          float r = preview ? 1F : actual.isAir() ? .45F : 1F;
          float g = preview ? 1F : actual.isAir() ? .85F : .25F;
          float b = preview ? 1F : actual.isAir() ? 1F : .25F;
          float shade = quad.isShade() ? FACE_SHADE[quad.getDirection().ordinal()] : 1F;
          job.quads.add(
              new PreviewMeshData.Quad(
                  quad.getVertices(),
                  (float) (entry.position().getX() - job.origin.x),
                  (float) (entry.position().getY() - job.origin.y),
                  (float) (entry.position().getZ() - job.origin.z),
                  (tint >> 16 & 255) * r * shade,
                  (tint >> 8 & 255) * g * shade,
                  (tint & 255) * b * shade,
                  preview ? 255 : 100,
                  translucent));
        }
      }
    }
  }

  private static void captureLiquid(SectionMeshBuild job, PreviewSceneData.Entry entry, BlockState state, BlockState actual, PreviewBlockView view, Minecraft mc, boolean preview) {
    var fluid = state.getFluidState();
    if (fluid.isEmpty()) {
      return;
    }
    var collector = new FluidQuadCollector();
    mc.getBlockRenderer().renderLiquid(entry.position(), view, collector, state, fluid);
    int sectionX = entry.position().getX() & 15;
    int sectionY = entry.position().getY() & 15;
    int sectionZ = entry.position().getZ() & 15;
    for (var quad : collector.quads()) {
      int color = quad.color();
      float red = color >> 16 & 255, green = color >> 8 & 255, blue = color & 255;
      int alpha = color >>> 24;
      if (!preview) {
        red *= actual.isAir() ? .45F : 1F;
        green *= actual.isAir() ? .85F : .25F;
        blue *= actual.isAir() ? 1F : .25F;
        alpha = alpha * 100 / 255;
      }
      job.quads.add(
          new PreviewMeshData.Quad(
              toBlockLocalVertices(quad.vertices(), sectionX, sectionY, sectionZ),
              (float) (entry.position().getX() - job.origin.x),
              (float) (entry.position().getY() - job.origin.y),
              (float) (entry.position().getZ() - job.origin.z),
              red,
              green,
              blue,
              alpha,
              true));
    }
  }

  private static int[] toBlockLocalVertices(int[] vertices, int sectionX, int sectionY, int sectionZ) {
    int[] local = vertices.clone();
    for (int index = 0; index < local.length; index += 6) {
      local[index] = Float.floatToRawIntBits(Float.intBitsToFloat(local[index]) - sectionX);
      local[index + 1] = Float.floatToRawIntBits(Float.intBitsToFloat(local[index + 1]) - sectionY);
      local[index + 2] = Float.floatToRawIntBits(Float.intBitsToFloat(local[index + 2]) - sectionZ);
    }
    return local;
  }

  private static final class FluidQuadCollector implements VertexConsumer {
    private final List<Quad> quads = new ArrayList<>();
    private final int[] vertices = new int[24];
    private int vertex;
    private float x, y, z, u, v;
    private int color = -1;
    private boolean active;

    private record Quad(int[] vertices, int color) {
    }

    List<Quad> quads() {
      finishVertex();
      return quads;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
      finishVertex();
      this.x = x;
      this.y = y;
      this.z = z;
      u = 0F;
      v = 0F;
      color = -1;
      active = true;
      return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
      color = alpha << 24 | red << 16 | green << 8 | blue;
      return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
      this.u = u;
      this.v = v;
      return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
      return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
      return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
      return this;
    }

    private void finishVertex() {
      if (!active) {
        return;
      }
      int offset = vertex++ * 6;
      vertices[offset] = Float.floatToRawIntBits(x);
      vertices[offset + 1] = Float.floatToRawIntBits(y);
      vertices[offset + 2] = Float.floatToRawIntBits(z);
      vertices[offset + 3] = 0;
      vertices[offset + 4] = Float.floatToRawIntBits(u);
      vertices[offset + 5] = Float.floatToRawIntBits(v);
      active = false;
      if (vertex == 4) {
        quads.add(new Quad(vertices.clone(), color));
        vertex = 0;
      }
    }
  }

}
