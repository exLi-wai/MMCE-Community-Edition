package com.lw.modularmachine.client.preview;

import com.lw.modularmachine.network.ProjectionPayloads;
import com.lw.modularmachine.ModularMachine;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Render-thread model capture / GPU ownership, detached background vertex
 * packing.
 */
final class SectionMeshCache implements AutoCloseable {
  private static final float[] FACE_SHADE = {.5F, 1F, .8F, .8F, .6F, .6F};
  private static final Direction[] FACES = Direction.values();
  private static final long GPU_BYTES = 96L << 20;
  private static final int UPLOAD_BYTES = 2 << 20;
  private final Map<Long, Mesh> meshes = new LinkedHashMap<>(16, .75F, true);
  private final Map<Long, Long> revisions = new HashMap<>();
  private final Map<Long, Long> failed = new HashMap<>();
  private final Map<Long, List<BlockState>> observed = new HashMap<>();
  private final Set<Long> dirty = new HashSet<>();
  private final List<PreviewRenderScene.Section> visible = new ArrayList<>();
  private final Set<Long> resident = new HashSet<>();
  private PreviewRenderScene scene;
  private boolean preview;
  private int previewLayer = Integer.MIN_VALUE, previewStep, inspect;
  private long usedBytes;
  private Build build;

  private static final class Page implements AutoCloseable {
    final VertexBuffer buffer;
    final boolean translucent;
    final MeshData.SortState sorting;
    Vec3 sortedEye;
    Page(VertexBuffer buffer, boolean translucent, MeshData.SortState sorting, Vec3 sortedEye) {
      this.buffer = buffer;
      this.translucent = translucent;
      this.sorting = sorting;
      this.sortedEye = sortedEye;
    }
    @Override
    public void close() {
      buffer.close();
    }
  }
  private record Mesh(List<Page> pages, Vec3 origin, int bytes) implements AutoCloseable {
    @Override
    public void close() {
      pages.forEach(Page::close);
    }
  }
  private static final class Build implements AutoCloseable {
    final PreviewRenderScene.Section section;
    final long revision;
    final Vec3 origin;
    final List<BlockState> states;
    final List<PreviewMeshData.Quad> quads = new ArrayList<>();
    final List<Page> uploaded = new ArrayList<>();
    final RandomSource random = RandomSource.create();
    int cursor, uploadCursor, bytes;
    CompletableFuture<List<PreviewMeshData.Page>> packing;
    List<PreviewMeshData.Page> packed;
    Build(PreviewRenderScene.Section section, long revision, List<BlockState> states) {
      this.section = section;
      this.revision = revision;
      this.states = states;
      var pos = SectionPos.of(section.spatialKey()).origin();
      origin = Vec3.atLowerCornerOf(pos);
    }
    @Override
    public void close() {
      uploaded.forEach(Page::close);
      uploaded.clear();
      quads.clear();
      packed = null;
    }
  }

  void setScene(PreviewRenderScene value) {
    close();
    scene = value;
  }
  void setPreviewScene(PreviewRenderScene value) {
    setScene(value);
    preview = true;
  }

  private void invalidate(PreviewRenderScene.Section section) {
    dirty.add(section.key());
    revisions.merge(section.key(), 1L, Long::sum);
  }

  void sectionChanged(int x, int y, int z) {
    if (scene == null || preview) {
      return;
    }
    for (var section : scene.atSection(SectionPos.asLong(x, y, z))) {
      invalidate(section);
    }
  }

  void tick() {
    if (scene == null || preview || visible.isEmpty() || Minecraft.getInstance().level == null) {
      return;
    }
    var section = visible.get(Math.floorMod(inspect++, visible.size()));
    var states = captureStates(section);
    if (!states.equals(observed.get(section.key())) && !dirty.contains(section.key())) {
      invalidate(section);
    }
  }

  void setPreviewState(int layer, int step) {
    if (scene == null || previewLayer == layer && previewStep == step) {
      return;
    }
    int previousLayer = previewLayer, previousStep = previewStep;
    previewLayer = layer;
    previewStep = step;
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
    return java.util.Collections.unmodifiableList(states);
  }
  private BlockState shown(ProjectionPayloads.Entry entry) {
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
    if (build != null
        && (build.revision != revisions.getOrDefault(build.section.key(), 0L)
            || !resident.contains(build.section.key()))) {
      build.close();
      build = null;
    }
    while (System.nanoTime() < deadline) {
      if (build == null) {
        for (var section : visible) {
          if (!resident.contains(section.key())) {
            continue;
          }
          if (java.util.Objects.equals(failed.get(section.key()), revisions.getOrDefault(section.key(), 0L))) {
            continue;
          }
          if (!meshes.containsKey(section.key()) || dirty.contains(section.key())) {
            build = new Build(section, revisions.getOrDefault(section.key(), 0L), captureStates(section));
            break;
          }
        }
        if (build == null) {
          break;
        }
      }
      var current = build;
      try {
        if (current.cursor < current.section.entries().size()) {
          captureModel(current, current.cursor++);
          continue;
        }
        if (current.packing == null) {
          current.packing = PreviewMeshData.submit(current.quads);
          if (current.packing != null) {
            current.quads.clear();
          }
          break;
        }
        if (!current.packing.isDone()) {
          break;
        }
        if (current.packed == null) {
          current.packed = current.packing.join();
        }
        if (current.uploadCursor < current.packed.size()) {
          var data = current.packed.get(current.uploadCursor++);
          current.uploaded.add(uploadPage(data, current.origin, eye));
          int bytes = data.vertices().length * Integer.BYTES + data.vertices().length / 4 * Integer.BYTES;
          current.bytes += bytes;
          uploadBytes += bytes;
          if (uploadBytes >= UPLOAD_BYTES) {
            break;
          }
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
        dirty.remove(current.section.key());
        build.close();
        build = null;
        evict();
      } catch (RuntimeException failure) {
        failed.put(current.section.key(), current.revision);
        current.close();
        build = null;
        ModularMachine.LOGGER.warn(
            "Preview batch {} failed; keeping its previous mesh until invalidation",
            current.section.key(),
            failure);
      }
    }
  }

  private void captureModel(Build job, int index) {
    var entry = job.section.entries().get(index);
    var captured = job.states.get(index);
    // Schematic data is already available even when the real chunk is not.
    // Unknown world positions remain visible as missing, without loading chunks.
    var actual = captured == null ? Blocks.AIR.defaultBlockState() : captured;
    var state = shown(entry);
    if (state == null
        || state.getRenderShape() != RenderShape.MODEL
        || !preview && captured != null && entry.matches(actual)
        || preview && previewLayer != Integer.MIN_VALUE && entry.position().getY() != previewLayer) {
      return;
    }
    var mc = Minecraft.getInstance();
    var view = new PreviewBlockView(scene, previewLayer, previewStep);
    var model = mc.getBlockRenderer().getBlockModel(state);
    var random = job.random;
    long seed = state.getSeed(entry.position());
    random.setSeed(seed);
    var types = model.getRenderTypes(state, random, ModelData.EMPTY);
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
        for (var quad : model.getQuads(state, face, random, ModelData.EMPTY, type)) {
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

  private static Page uploadPage(PreviewMeshData.Page page, Vec3 origin, Vec3 eye) {
    try (var bytes = new ByteBufferBuilder(page.vertices().length * 4 + 65536)) {
      var builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
      var vertices = page.vertices();
      for (int i = 0; i < vertices.length; i += 6) {
        int color = vertices[i + 5];
        builder
            .addVertex(
                Float.intBitsToFloat(vertices[i]),
                Float.intBitsToFloat(vertices[i + 1]),
                Float.intBitsToFloat(vertices[i + 2]))
            .setUv(Float.intBitsToFloat(vertices[i + 3]), Float.intBitsToFloat(vertices[i + 4]))
            .setColor(color >> 16 & 255, color >> 8 & 255, color & 255, color >>> 24);
      }
      var data = builder.buildOrThrow();
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
      return new Page(buffer, page.translucent(), sorting, eye);
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

  void drawPreview(Matrix4f view, Matrix4f projection) {
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

  @Override
  public void close() {
    if (build != null) {
      build.close();
      build = null;
    }
    meshes.values().forEach(Mesh::close);
    meshes.clear();
    revisions.clear();
    failed.clear();
    observed.clear();
    dirty.clear();
    visible.clear();
    resident.clear();
    scene = null;
    usedBytes = 0;
    inspect = 0;
    preview = false;
    previewLayer = Integer.MIN_VALUE;
    previewStep = 0;
  }
}
