package com.lw.modularmachine.client.preview;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.client.preview.PreviewSceneData;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.ApiStatus;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Render-thread model capture / GPU ownership, detached background vertex
 * packing.
 */
@ApiStatus.Internal
public final class SectionMeshCache implements AutoCloseable {
  private static final Direction[] FACES = Direction.values();
  private static final long GPU_BYTES = 96L << 20;
  private static final int UPLOAD_BYTES = 2 << 20;
  private static final int IN_FLIGHT_BUILDS = 4;
  private final TransparentSectionBatch transparency = new TransparentSectionBatch();
  private final Map<Long, Mesh> meshes = new LinkedHashMap<>(16, .75F, true);
  private final Map<Long, Long> revisions = new HashMap<>();
  private final Map<Long, Long> failed = new HashMap<>();
  private final Map<Long, List<BlockState>> observed = new HashMap<>();
  private final Set<Long> dirty = new HashSet<>();
  private final Set<Long> worldChecks = new HashSet<>();
  private final Set<Long> modelSensitive = new HashSet<>();
  private final Set<Long> modelChanges = new HashSet<>();
  private final List<PreviewRenderScene.Section> visible = new ArrayList<>();
  private final Set<Long> resident = new HashSet<>();
  private PreviewRenderScene scene;
  private PreviewBlockView blockView;
  private PreviewBlockEntityRenderer blockEntities;
  private boolean preview;
  private int previewLayer = Integer.MIN_VALUE, previewStep, inspect;
  private long usedBytes;
  private final Map<Long, SectionMeshBuild> builds = new LinkedHashMap<>();
  private record Mesh(List<SectionMeshPage> pages, Vec3 origin, int bytes) implements AutoCloseable {
    @Override
    public void close() {
      pages.forEach(SectionMeshPage::close);
    }
  }
  void setScene(PreviewRenderScene value) {
    close();
    scene = value;
    blockView = new PreviewBlockView(scene, Integer.MIN_VALUE, 0, true);
    blockEntities = new PreviewBlockEntityRenderer(scene);
  }
  public void setPreviewScene(PreviewRenderScene value) {
    setScene(value);
    preview = true;
    blockView = new PreviewBlockView(scene, Integer.MIN_VALUE, 0);
  }

  private void invalidate(PreviewRenderScene.Section section) {
    dirty.add(section.key());
    revisions.merge(section.key(), 1L, Long::sum);
  }

  void sectionChanged(int x, int y, int z) {
    if (scene == null || preview) {
      return;
    }
    for (int dx = -1; dx <= 1; dx++) {
      for (int dy = -1; dy <= 1; dy++) {
        for (int dz = -1; dz <= 1; dz++) {
          for (var section : scene.atSection(SectionPos.asLong(x + dx, y + dy, z + dz))) {
            worldChecks.add(section.key());
            modelChanges.add(section.key());
          }
        }
      }
    }
  }

  void tick() {
    if (scene == null || preview || visible.isEmpty() || Minecraft.getInstance().level == null) {
      return;
    }
    var section = visible.get(Math.floorMod(inspect++, visible.size()));
    worldChecks.add(section.key());
  }

  public void setPreviewState(int layer, int step) {
    if (scene == null || previewLayer == layer && previewStep == step) {
      return;
    }
    int previousLayer = previewLayer, previousStep = previewStep;
    previewLayer = layer;
    previewStep = step;
    blockView.setState(layer, step);
    if (layer != previousLayer) {
      for (var section : scene.sections()) {
        if (intersects(section, layer) || intersects(section, previousLayer)) {
          invalidate(section);
        }
      }
    }
    if (step != previousStep) {
      // Only changed alternatives and their immediate neighbors affect geometry.
      for (var entry : scene.animated()) {
        if (Math.floorMod(previousStep, entry.states().size()) == Math.floorMod(step, entry.states().size())) {
          continue;
        }
        invalidateAt(entry.position());
        for (var face : FACES) {
          invalidateAt(entry.position().relative(face));
        }
      }
    }
  }

  private void invalidateAt(BlockPos position) {
    for (var section : scene.atSection(SectionPos.asLong(position))) {
      invalidate(section);
    }
  }
  private static boolean intersects(PreviewRenderScene.Section section, int layer) {
    return layer == Integer.MIN_VALUE || layer >= section.bounds().minY && layer < section.bounds().maxY;
  }

  private List<BlockState> captureStates(PreviewRenderScene.Section section) {
    var level = Minecraft.getInstance().level;
    var states = new ArrayList<BlockState>(section.entries().size());
    for (var entry : section.entries()) {
      states.add(
          preview ? shown(entry) : level != null && level.hasChunkAt(entry.position()) ? level
              .getBlockState(entry.position()) : null);
    }
    return Collections.unmodifiableList(states);
  }
  private BlockState shown(PreviewSceneData.Entry entry) {
    return entry.states().isEmpty() ? null : entry.states().get(Math.floorMod(previewStep, entry.states().size()));
  }

  private void prepareVisible(Predicate<PreviewRenderScene.Section> predicate, Vec3 eye) {
    visible.clear();
    resident.clear();
    if (scene == null) {
      return;
    }
    for (var section : scene.sections()) {
      if ((!preview || intersects(section, previewLayer)) && predicate.test(section)) {
        visible.add(section);
      }
    }
    visible.sort(Comparator.comparingDouble(section -> section.bounds().distanceToSqr(eye)));
    for (var section : visible) {
      // The cache target controls off-screen retention, never visible geometry.
      // Large structures must not turn into a sphere of nearby admitted batches.
      resident.add(section.key());
    }
    evict();
  }

  private void update(Vec3 eye) {
    if (scene == null || Minecraft.getInstance().level == null) {
      return;
    }
    long deadline = System.nanoTime() + 2_000_000L;
    int uploadBytes = 0;
    // Lighting and neighbor notifications often repeat without a state change.
    // Coalesce them, and compare against in-flight capture before discarding it.
    long validationDeadline = Math.min(deadline, System.nanoTime() + 250_000L);
    for (var section : visible) {
      if (System.nanoTime() >= validationDeadline) {
        break;
      }
      if (!worldChecks.remove(section.key())) {
        continue;
      }
      var current = builds.get(section.key());
      var baseline = current == null ? observed.get(section.key()) : current.states;
      boolean modelChanged = modelChanges.remove(section.key())
          && (modelSensitive.contains(section.key()) || current != null && current.modelSensitive);
      if (baseline != null && (modelChanged || !captureStates(section).equals(baseline))) {
        invalidate(section);
      }
    }
    var stale = builds.values().iterator();
    while (stale.hasNext()) {
      var job = stale.next();
      if (job.revision != revisions.getOrDefault(job.section.key(), 0L) || !resident.contains(job.section.key())) {
        job.close();
        stale.remove();
      }
    }
    while (System.nanoTime() < deadline) {
      boolean progressed = false;
      var iterator = builds.values().iterator();
      while (iterator.hasNext() && System.nanoTime() < deadline) {
        var current = iterator.next();
        try {
          if (current.cursor < current.section.entries().size()) {
            // Small slices let ready uploads and other captures progress together.
            int end = Math.min(current.cursor + 32, current.section.entries().size());
            while (current.cursor < end && System.nanoTime() < deadline) {
              PreviewModelCapture.capture(current, current.cursor++, preview, previewLayer, previewStep, blockView);
              progressed = true;
            }
          }
          if (current.cursor < current.section.entries().size()) {
            continue;
          }
          if (current.packing == null) {
            current.packing = PreviewMeshData.submit(current.quads);
            if (current.packing != null) {
              current.quads.clear();
              progressed = true;
            }
          }
          if (current.packing == null || !current.packing.isDone()) {
            continue;
          }
          if (current.packed == null) {
            current.packed = current.packing.join();
          }
          if (current.uploadCursor < current.packed.size() && uploadBytes < UPLOAD_BYTES) {
            var data = current.packed.get(current.uploadCursor++);
            current.uploaded.add(SectionMeshPage.upload(data, current.origin, eye));
            int bytes = data.vertices().length * Integer.BYTES + data.vertices().length / 4 * Integer.BYTES;
            current.bytes += bytes;
            uploadBytes += bytes;
            progressed = true;
          }
          if (current.uploadCursor < current.packed.size()) {
            continue;
          }
          var mesh = new Mesh(List.copyOf(current.uploaded), current.origin, current.bytes);
          current.uploaded.clear();
          var old = meshes.put(current.section.key(), mesh);
          if (old != null) {
            usedBytes -= old.bytes();
            old.close();
          }
          usedBytes += mesh.bytes();
          observed.put(current.section.key(), current.states);
          if (current.modelSensitive) {
            modelSensitive.add(current.section.key());
          } else {
            modelSensitive.remove(current.section.key());
          }
          dirty.remove(current.section.key());
          current.close();
          iterator.remove();
          evict();
          progressed = true;
        } catch (RuntimeException failure) {
          failed.put(current.section.key(), current.revision);
          current.close();
          iterator.remove();
          ModularMachine.LOGGER.warn(
              "Preview batch {} failed; keeping its previous mesh until invalidation",
              current.section.key(),
              failure);
          progressed = true;
        }
      }
      for (var section : visible) {
        if (builds.size() >= IN_FLIGHT_BUILDS || System.nanoTime() >= deadline) {
          break;
        }
        if (!resident.contains(section.key())
            || builds.containsKey(section.key())
            || Objects.equals(failed.get(section.key()), revisions.getOrDefault(section.key(), 0L))) {
          continue;
        }
        if (!meshes.containsKey(section.key()) || dirty.contains(section.key())) {
          builds.put(
              section.key(),
              new SectionMeshBuild(section, revisions.getOrDefault(section.key(), 0L), captureStates(section)));
          progressed = true;
        }
      }
      if (!progressed || uploadBytes >= UPLOAD_BYTES) {
        break;
      }
    }
  }
  private void evict() {
    var iterator = meshes.entrySet().iterator();
    while (usedBytes > GPU_BYTES && iterator.hasNext()) {
      var entry = iterator.next();
      if (resident.contains(entry.getKey())) {
        continue;
      }
      usedBytes -= entry.getValue().bytes();
      entry.getValue().close();
      observed.remove(entry.getKey());
      iterator.remove();
    }
  }

  void draw(RenderLevelStageEvent event) {
    var eye = event.getCamera().getPosition();
    prepareVisible(section -> event.getFrustum().isVisible(section.bounds()), eye);
    update(eye);
    drawMeshes(event.getModelViewMatrix(), event.getProjectionMatrix(), eye, eye);
  }

  public void drawPreview(Matrix4f view, Matrix4f projection) {
    var eyeVector = new Matrix4f(view).invert().transformPosition(new Vector3f());
    var eye = new Vec3(eyeVector.x, eyeVector.y, eyeVector.z);
    var frustum = new FrustumIntersection(new Matrix4f(projection).mul(view));
    prepareVisible(
        section -> frustum.testAab(
            (float) section.bounds().minX,
            (float) section.bounds().minY,
            (float) section.bounds().minZ,
            (float) section.bounds().maxX,
            (float) section.bounds().maxY,
            (float) section.bounds().maxZ),
        eye);
    update(eye);
    drawMeshes(view, projection, Vec3.ZERO, eye);
  }

  private void drawMeshes(Matrix4f view, Matrix4f projection, Vec3 translation, Vec3 eye) {
    transparency.update(transparentPages(), eye);
    RenderSystem.enableBlend();
    RenderSystem.defaultBlendFunc();
    RenderSystem.enableDepthTest();
    RenderSystem.depthMask(preview);
    RenderSystem.disableCull();
    RenderSystem.setShaderColor(1, 1, 1, 1);
    RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
    RenderSystem.setShaderTexture(0, InventoryMenu.BLOCK_ATLAS);
    long sortDeadline = System.nanoTime() + 1_000_000L;
    try {
      for (int pass = 0; pass < 2; pass++) {
        boolean transparent = pass == 1;
        if (transparent) {
          blockEntities.draw(visible, blockView, preview, previewLayer, view, translation);
          RenderSystem.enableBlend();
          RenderSystem.defaultBlendFunc();
          RenderSystem.enableDepthTest();
          RenderSystem.disableCull();
          RenderSystem.setShaderColor(1, 1, 1, 1);
          RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
          RenderSystem.setShaderTexture(0, InventoryMenu.BLOCK_ATLAS);
          if (transparency.ready()) {
            RenderSystem.depthMask(false);
            transparency.draw(view, projection, translation);
            continue;
          }
        }
        RenderSystem.depthMask(preview && !transparent);
        for (int i = 0; i < visible.size(); i++) {
          var section = visible.get(transparent ? visible.size() - 1 - i : i);
          if (!resident.contains(section.key())) {
            continue;
          }
          var mesh = meshes.get(section.key());
          if (mesh == null) {
            continue;
          }
          var matrix = new Matrix4f(view).translate(
              (float) (mesh.origin().x - translation.x),
              (float) (mesh.origin().y - translation.y),
              (float) (mesh.origin().z - translation.z));
          for (var page : mesh.pages()) {
            if (page.translucent != transparent) {
              continue;
            }
            page.buffer.bind();
            if (transparent
                && page.sorting != null
                && page.sortedEye.distanceToSqr(eye) > .25
                && System.nanoTime() < sortDeadline) {
              try (var indices = new ByteBufferBuilder(65536)) {
                var sorted = page.sorting.buildSortedIndexBuffer(
                    indices,
                    VertexSorting.byDistance(
                        (float) (eye.x - mesh.origin().x),
                        (float) (eye.y - mesh.origin().y),
                        (float) (eye.z - mesh.origin().z)));
                if (sorted != null) {
                  page.buffer.uploadIndexBuffer(sorted);
                }
                page.sortedEye = eye;
              }
            }
            page.buffer.drawWithShader(matrix, projection, GameRenderer.getPositionTexColorShader());
          }
        }
      }
    } finally {
      VertexBuffer.unbind();
      RenderSystem.depthMask(true);
      RenderSystem.enableCull();
      RenderSystem.disableBlend();
    }
  }

  private List<TransparentSectionBatch.TransparentPage> transparentPages() {
    var pages = new ArrayList<TransparentSectionBatch.TransparentPage>();
    for (var section : visible.stream().sorted(Comparator.comparingLong(PreviewRenderScene.Section::key)).toList()) {
      var mesh = meshes.get(section.key());
      if (mesh != null) {
        for (var page : mesh.pages()) {
          if (page.translucent) {
            pages.add(new TransparentSectionBatch.TransparentPage(page, mesh.origin()));
          }
        }
      }
    }
    return pages;
  }

  @Override
  public void close() {
    transparency.close();
    builds.values().forEach(SectionMeshBuild::close);
    builds.clear();
    meshes.values().forEach(Mesh::close);
    meshes.clear();
    revisions.clear();
    failed.clear();
    observed.clear();
    dirty.clear();
    worldChecks.clear();
    modelSensitive.clear();
    modelChanges.clear();
    visible.clear();
    resident.clear();
    scene = null;
    blockView = null;
    if (blockEntities != null) {
      blockEntities.close();
      blockEntities = null;
    }
    usedBytes = 0;
    inspect = 0;
    preview = false;
    previewLayer = Integer.MIN_VALUE;
    previewStep = 0;
  }
}
