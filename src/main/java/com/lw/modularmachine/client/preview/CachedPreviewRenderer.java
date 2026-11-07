package com.lw.modularmachine.client.preview;

import com.lw.modularmachine.api.client.preview.PreviewCamera;
import com.lw.modularmachine.api.client.preview.PreviewRenderer;
import com.lw.modularmachine.api.client.preview.PreviewSceneData;
import com.lw.modularmachine.api.client.preview.PreviewViewport;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.lang.ref.WeakReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.jetbrains.annotations.ApiStatus;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Shared GUI projection, clipping, picking and mesh ownership. */
@ApiStatus.Internal
@EventBusSubscriber(modid = "mmcece", value = Dist.CLIENT)
public final class CachedPreviewRenderer implements PreviewRenderer {
  private static final Map<CachedPreviewRenderer, WeakReference<Screen>> VISIBLE = new IdentityHashMap<>();
  private static long frame;
  private final SectionMeshCache cache = new SectionMeshCache();
  private PreviewRenderScene scene;
  private List<PreviewSceneData.Entry> entityRenderedEntries = List.of();
  private AABB bounds;
  private Matrix4f inverse;
  private Matrix4f guiTransform;
  private Clip clip;
  private int shownLayer = ALL_LAYERS;
  private boolean cacheReady;
  private long drawnFrame;

  private record Clip(int left, int top, int right, int bottom) {
    boolean contains(double x, double y) {
      return x >= left && x < right && y >= top && y < bottom;
    }
  }

  @Override
  public void setScene(PreviewSceneData data) {
    RenderSystem.assertOnRenderThread();
    Objects.requireNonNull(data, "scene");
    close();
    scene = PreviewRenderScene.create(data);
    entityRenderedEntries = data.entries().stream().filter(
        entry -> entry.states().stream().anyMatch(state -> state.getRenderShape() == RenderShape.ENTITYBLOCK_ANIMATED))
        .toList();
    bounds = data.bounds();
  }

  @Override
  public void render(GuiGraphics graphics, PreviewViewport viewport, PreviewCamera camera, int layer, int step) {
    RenderSystem.assertOnRenderThread();
    Objects.requireNonNull(graphics, "graphics");
    Objects.requireNonNull(viewport, "viewport");
    Objects.requireNonNull(camera, "camera");
    inverse = null;
    guiTransform = null;
    clip = null;
    if (scene == null || viewport.width() == 0 || viewport.height() == 0 || Minecraft.getInstance().level == null) {
      return;
    }
    var screen = Minecraft.getInstance().screen;
    if (screen != null) {
      VISIBLE.put(this, new WeakReference<>(screen));
    }
    drawnFrame = frame;
    if (!cacheReady) {
      cache.setPreviewScene(scene);
      cacheReady = true;
    }
    cache.setPreviewState(layer, step);
    shownLayer = layer;
    guiTransform = new Matrix4f(graphics.pose().last().pose());
    var rectangle = screenClip(guiTransform, viewport);
    graphics.flush();
    graphics.enableScissor(rectangle.left(), rectangle.top(), rectangle.right(), rectangle.bottom());
    graphics.pose().pushPose();
    try {
      float scale = viewport.baseScale() * camera.zoom();
      var center = bounds.getCenter();
      boolean layered = layer != ALL_LAYERS;
      graphics.pose().translate(viewport.anchorX() + camera.panX(), viewport.anchorY() + camera.panY(), 150);
      graphics.pose().scale(scale, -scale, scale);
      graphics.pose().mulPose(
          new Quaternionf().rotationXYZ(
              (float) Math.toRadians(layered ? 90 : camera.pitch()),
              (float) Math.toRadians(layered ? 0 : camera.yaw()),
              0));
      graphics.pose().translate(-center.x, layered ? -layer - .5D : -center.y, -center.z);
      var transform = new Matrix4f(graphics.pose().last().pose());
      inverse = new Matrix4f(transform).invert();
      clip = rectangle;
      renderEntityBlocks(graphics, layer, step);
      cache.drawPreview(
          new Matrix4f(RenderSystem.getModelViewMatrix()).mul(transform),
          RenderSystem.getProjectionMatrix());
    } finally {
      graphics.pose().popPose();
      graphics.disableScissor();
    }
  }

  /** Item-rendered block entities cannot be captured as baked model quads. */
  private void renderEntityBlocks(GuiGraphics graphics, int layer, int step) {
    if (entityRenderedEntries.isEmpty()) {
      return;
    }
    var minecraft = Minecraft.getInstance();
    var buffers = graphics.bufferSource();
    Lighting.setupFor3DItems();
    RenderSystem.enableDepthTest();
    RenderSystem.depthMask(true);
    RenderSystem.setShaderColor(1, 1, 1, 1);
    try (var colorScope = PreviewBlockView.pushMachineColor(scene.machineColor())) {
      try {
        for (var entry : entityRenderedEntries) {
          if (layer != ALL_LAYERS && entry.position().getY() != layer) {
            continue;
          }
          var state = entry.states().get(Math.floorMod(step, entry.states().size()));
          if (state.getRenderShape() != RenderShape.ENTITYBLOCK_ANIMATED) {
            continue;
          }
          graphics.pose().pushPose();
          try {
            var position = entry.position();
            graphics.pose().translate(position.getX(), position.getY(), position.getZ());
            minecraft.getBlockRenderer().renderSingleBlock(
                state,
                graphics.pose(),
                buffers,
                LightTexture.FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY);
          } finally {
            graphics.pose().popPose();
          }
        }
      } finally {
        // Flush under the preview scissor, before cached translucent geometry.
        buffers.endBatch();
      }
    } finally {
      Lighting.setupFor3DItems();
    }
  }

  private static Clip screenClip(Matrix4f transform, PreviewViewport viewport) {
    float left = Float.POSITIVE_INFINITY, top = Float.POSITIVE_INFINITY;
    float right = Float.NEGATIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY;
    for (int index = 0; index < 4; index++) {
      var point = transform.transformPosition(
          new Vector3f(
              viewport.x() + (index % 2 == 0 ? 0 : viewport.width()),
              viewport.y() + (index < 2 ? 0 : viewport.height()),
              0));
      left = Math.min(left, point.x);
      top = Math.min(top, point.y);
      right = Math.max(right, point.x);
      bottom = Math.max(bottom, point.y);
    }

    return new Clip((int) Math.floor(left), (int) Math.floor(top), (int) Math.ceil(right), (int) Math.ceil(bottom));
  }

  @Override
  public int pick(double mouseX, double mouseY) {
    RenderSystem.assertOnRenderThread();
    if (inverse == null || guiTransform == null || clip == null) {
      return -1;
    }
    var screenPoint = guiTransform.transformPosition(new Vector3f((float) mouseX, (float) mouseY, 0));
    if (!clip.contains(screenPoint.x, screenPoint.y)) {
      return -1;
    }
    var from = inverse.transformPosition(new Vector3f(screenPoint.x, screenPoint.y, 10000));
    var to = inverse.transformPosition(new Vector3f(screenPoint.x, screenPoint.y, -10000));
    var start = new Vec3(from.x, from.y, from.z);
    var end = new Vec3(to.x, to.y, to.z);
    double nearest = Double.MAX_VALUE;
    int selected = -1;
    for (var section : scene.sections()) {
      if (section.bounds().clip(start, end).isEmpty()) {
        continue;
      }
      for (var entry : section.entries()) {
        if (entry.states().isEmpty() || shownLayer != ALL_LAYERS && entry.position().getY() != shownLayer) {
          continue;
        }
        var hit = new AABB(entry.position()).clip(start, end);
        if (hit.isPresent() && hit.orElseThrow().distanceToSqr(start) < nearest) {
          nearest = hit.orElseThrow().distanceToSqr(start);
          selected = scene.indexAt(entry.position());
        }
      }
    }

    return selected;
  }

  @Override
  public void invalidate() {
    RenderSystem.assertOnRenderThread();
    cache.close();
    cacheReady = false;
    inverse = null;
    guiTransform = null;
    clip = null;
  }

  @Override
  public void close() {
    invalidate();
    VISIBLE.remove(this);
  }

  public static void invalidateAll() {
    RenderSystem.assertOnRenderThread();
    VISIBLE.keySet().forEach(CachedPreviewRenderer::invalidate);
  }

  @SubscribeEvent
  public static void beforeRender(ScreenEvent.Render.Pre event) {
    frame++;
  }

  @SubscribeEvent
  public static void afterRender(ScreenEvent.Render.Post event) {
    var iterator = VISIBLE.entrySet().iterator();
    while (iterator.hasNext()) {
      var entry = iterator.next();
      var screen = entry.getValue().get();
      if (screen == null || screen == event.getScreen() && entry.getKey().drawnFrame != frame) {
        entry.getKey().invalidate();
        iterator.remove();
      }
    }
  }

  @SubscribeEvent
  public static void closing(ScreenEvent.Closing event) {
    releaseScreen(event.getScreen());
  }

  @SubscribeEvent
  public static void initializing(ScreenEvent.Init.Pre event) {
    releaseScreen(event.getScreen());
  }

  private static void releaseScreen(Screen screen) {
    var iterator = VISIBLE.entrySet().iterator();
    while (iterator.hasNext()) {
      var entry = iterator.next();
      var current = entry.getValue().get();
      if (current == null || current == screen) {
        entry.getKey().invalidate();
        iterator.remove();
      }
    }
  }

  @SubscribeEvent
  public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
    invalidateAll();
    VISIBLE.clear();
  }
}
