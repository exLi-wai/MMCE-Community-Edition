package com.lw.modularmachine.client.preview;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.client.preview.PreviewSceneData;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.ApiStatus;
import org.joml.Matrix4f;

/**
 * Internal rendering of unticked, detached visual block entities for both
 * hosts.
 */
@ApiStatus.Internal
final class PreviewBlockEntityRenderer implements AutoCloseable {
  private final Map<Long, List<PreviewSceneData.Entry>> entries = new HashMap<>();
  private final Map<RenderType, RenderType> ghosts = new HashMap<>();
  private final Set<BlockState> failed = new HashSet<>();
  private final Map<BlockState, Boolean> supported = new HashMap<>();
  private final Set<Long> classified = new HashSet<>();
  private ByteBufferBuilder storage;
  private MultiBufferSource.BufferSource buffers;

  PreviewBlockEntityRenderer(PreviewRenderScene scene) {
    for (var section : scene.sections()) {
      var selected = section.entries().stream().filter(
          entry -> entry.states().stream()
              .anyMatch(state -> state.hasBlockEntity() || state.getRenderShape() == RenderShape.ENTITYBLOCK_ANIMATED))
          .toList();
      if (!selected.isEmpty()) {
        entries.put(section.key(), selected);
      }
    }
  }

  void draw(List<PreviewRenderScene.Section> visible, PreviewBlockView view, boolean preview, int layer, Matrix4f matrix, Vec3 translation) {
    if (entries.isEmpty()) {
      return;
    }
    if (storage == null) {
      storage = new ByteBufferBuilder(65536);
      buffers = MultiBufferSource.immediate(storage);
    }
    var minecraft = Minecraft.getInstance();
    if (preview) {
      com.mojang.blaze3d.platform.Lighting.setupFor3DItems();
    }
    var pose = new PoseStack();
    var transform = new Matrix4f(RenderSystem.getModelViewMatrix()).invert().mul(matrix)
        .translate((float) -translation.x, (float) -translation.y, (float) -translation.z);
    pose.last().pose().set(transform);
    transform.get3x3(pose.last().normal()).invert().transpose();
    long classificationDeadline = System.nanoTime() + 1_000_000L;
    try (var color = PreviewBlockView.pushMachineColor(view.machineColor())) {
      for (var section : visible) {
        if (!classified.contains(section.key()) && entries.containsKey(section.key())) {
          if (System.nanoTime() >= classificationDeadline) {
            continue;
          }
          var drawable = entries.get(section.key()).stream()
              .filter(entry -> entry.states().stream().anyMatch(state -> supports(state, entry, view))).toList();
          if (drawable.isEmpty()) {
            entries.remove(section.key());
          } else {
            entries.put(section.key(), drawable);
          }
          classified.add(section.key());
        }
        for (var entry : entries.getOrDefault(section.key(), List.of())) {
          if (preview && layer != Integer.MIN_VALUE && entry.position().getY() != layer) {
            continue;
          }
          var state = view.getBlockState(entry.position());
          if (failed.contains(state) || !supports(state, entry, view)) {
            continue;
          }
          var actual = minecraft.level.hasChunkAt(entry.position()) ? minecraft.level
              .getBlockState(entry.position()) : null;
          if (!preview && actual != null && entry.matches(actual)) {
            continue;
          }
          boolean missing = actual == null || actual.isAir();
          MultiBufferSource target = preview ? buffers : type -> new GhostVertex(
              buffers.getBuffer(ghosts.computeIfAbsent(type, PreviewBlockEntityRenderer::ghost)),
              missing);
          pose.pushPose();
          try {
            pose.translate(entry.position().getX(), entry.position().getY(), entry.position().getZ());
            var entity = view.getBlockEntity(entry.position());
            boolean fallback = entity == null;
            if (entity != null) {
              fallback = minecraft.getBlockEntityRenderDispatcher()
                  .renderItem(entity, pose, target, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            }
            if (fallback && state.getRenderShape() == RenderShape.ENTITYBLOCK_ANIMATED) {
              minecraft.getBlockRenderer()
                  .renderSingleBlock(state, pose, target, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            }
          } catch (RuntimeException failure) {
            failed.add(state);
            ModularMachine.LOGGER.warn("Preview renderer requires unsupported world data for {}", state, failure);
          } finally {
            pose.popPose();
          }
        }
      }
    } finally {
      buffers.endBatch();
      RenderSystem.depthMask(true);
    }
  }

  private boolean supports(BlockState state, PreviewSceneData.Entry entry, PreviewBlockView view) {
    var known = supported.get(state);
    if (known != null) {
      return known;
    }
    if (state.getRenderShape() == RenderShape.ENTITYBLOCK_ANIMATED) {
      supported.put(state, true);
      return true;
    }
    // Only classify the active candidate; inactive alternatives remain eligible
    // until their own state is selected. Do not repeatedly instantiate the many
    // ordinary storage BEs which have no additional block entity renderer.
    if (view.getBlockState(entry.position()) != state) {
      return true;
    }
    var entity = view.getBlockEntity(entry.position());
    boolean result = entity != null
        && Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(entity) != null;
    supported.put(state, result);
    return result;
  }

  private static RenderType ghost(RenderType original) {
    return new RenderType(
        "mmcece_preview_" + original.name,
        original.format(),
        original.mode(),
        original.bufferSize(),
        original.affectsCrumbling(),
        true,
        () -> {
          original.setupRenderState();
          RenderSystem.enableBlend();
          RenderSystem.defaultBlendFunc();
          RenderSystem.enableDepthTest();
          RenderSystem.depthMask(false);
        },
        () -> {
          original.clearRenderState();
          RenderSystem.depthMask(true);
        }) {
    };
  }

  private record GhostVertex(VertexConsumer delegate, boolean missing) implements VertexConsumer {
    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
      delegate.addVertex(x, y, z);
      return this;
    }
    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
      delegate.setColor(
          missing ? red * 45 / 100 : red,
          missing ? green * 85 / 100 : green / 4,
          missing ? blue : blue / 4,
          alpha * 100 / 255);
      return this;
    }
    @Override
    public VertexConsumer setUv(float u, float v) {
      delegate.setUv(u, v);
      return this;
    }
    @Override
    public VertexConsumer setUv1(int u, int v) {
      delegate.setUv1(u, v);
      return this;
    }
    @Override
    public VertexConsumer setUv2(int u, int v) {
      delegate.setUv2(u, v);
      return this;
    }
    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
      delegate.setNormal(x, y, z);
      return this;
    }
  }

  @Override
  public void close() {
    if (storage != null) {
      storage.close();
      storage = null;
      buffers = null;
    }
    ghosts.clear();
    failed.clear();
    supported.clear();
    classified.clear();
    entries.clear();
  }
}
