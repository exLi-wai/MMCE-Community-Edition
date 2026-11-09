package com.lw.modularmachine.client.preview;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.client.preview.PreviewSceneData;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
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
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.ApiStatus;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

/**
 * Render-thread model capture / GPU ownership, detached background vertex
 * packing.
 */
@ApiStatus.Internal
public final class SectionMeshCache implements AutoCloseable {
  private static final float[] FACE_SHADE = {.5F, 1F, .8F, .8F, .6F, .6F};
  private static final Direction[] FACES = Direction.values();
  private static final long GPU_BYTES = 96L << 20;
  private static final int UPLOAD_BYTES = 2 << 20;
  private static final int IN_FLIGHT_BUILDS = 4;
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
  private final Map<Long, Build> builds = new LinkedHashMap<>();
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

  private record TransparentPage(Page page, Vec3 origin) {
  }

  private static final class Page implements AutoCloseable {
    final VertexBuffer buffer;
    final boolean translucent;
    final MeshData.SortState sorting;
    final float[] centers;
    final int[] vertices;
    Vec3 sortedEye;
    Page(VertexBuffer buffer, boolean translucent, MeshData.SortState sorting, Vec3 sortedEye, int[] vertices) {
      this.buffer = buffer;
      this.translucent = translucent;
      this.sorting = sorting;
      this.sortedEye = sortedEye;
      this.vertices = vertices;
      centers = TransparentQuadOrder.centers(vertices);
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
    boolean modelSensitive;
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
              captureModel(current, current.cursor++);
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
            current.uploaded.add(uploadPage(data, current.origin, eye));
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
              new Build(section, revisions.getOrDefault(section.key(), 0L), captureStates(section)));
          progressed = true;
        }
      }
      if (!progressed || uploadBytes >= UPLOAD_BYTES) {
        break;
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
        || state.getRenderShape() != RenderShape.MODEL && !(state.getBlock() instanceof LiquidBlock)
        || !preview && captured != null && entry.matches(actual)
        || preview && previewLayer != Integer.MIN_VALUE && entry.position().getY() != previewLayer) {
      return;
    }
    var mc = Minecraft.getInstance();
    var view = blockView;
    if (state.getBlock() instanceof LiquidBlock) {
      job.modelSensitive = true;
      captureLiquid(job, entry, state, actual, view, mc);
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

  private void captureLiquid(Build job, PreviewSceneData.Entry entry, BlockState state, BlockState actual, PreviewBlockView view, Minecraft mc) {
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

  private static Page uploadPage(PreviewMeshData.Page page, Vec3 origin, Vec3 eye) {
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
      return new Page(buffer, page.translucent(), sorting, eye, page.translucent() ? page.vertices() : new int[0]);
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
    updateTransparentOrder(eye);
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
          if (transparentMesh != null && mergedVertices == null && merging == null && transparentMesh.ready()) {
            RenderSystem.depthMask(false);
            drawTransparentOrder(view, projection, translation);
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

  private void updateTransparentOrder(Vec3 eye) {
    var pages = new ArrayList<TransparentPage>();
    for (var section : visible.stream().sorted(Comparator.comparingLong(PreviewRenderScene.Section::key)).toList()) {
      var mesh = meshes.get(section.key());
      if (mesh != null) {
        for (var page : mesh.pages()) {
          if (page.translucent) {
            pages.add(new TransparentPage(page, mesh.origin()));
          }
        }
      }
    }
    if (!pages.equals(transparentPages)) {
      clearTransparentOrder();
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
      clearTransparentOrder();
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

  private void drawTransparentOrder(Matrix4f view, Matrix4f projection, Vec3 translation) {
    var matrix = new Matrix4f(view).translate(
        (float) (transparentOrigin.x - translation.x),
        (float) (transparentOrigin.y - translation.y),
        (float) (transparentOrigin.z - translation.z));
    transparentMesh.draw(matrix, projection);
  }

  private void clearTransparentOrder() {
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

  @Override
  public void close() {
    clearTransparentOrder();
    builds.values().forEach(Build::close);
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
