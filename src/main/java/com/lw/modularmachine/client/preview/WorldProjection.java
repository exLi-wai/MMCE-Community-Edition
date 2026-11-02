package com.lw.modularmachine.client.preview;

import com.lw.modularmachine.network.ProjectionPayloads;
import com.lw.modularmachine.config.MachineClientConfig;
import com.lw.modularmachine.ModularMachine;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

@EventBusSubscriber(modid = "mmcece", value = Dist.CLIENT)
public final class WorldProjection {
  private static final SectionMeshCache CACHE = new SectionMeshCache();
  private static final ArrayList<ProjectionPayloads.Entry> pending = new ArrayList<>();
  private static ProjectionPayloads.Snapshot header;
  private static long latest = -1;
  private static long cancelledThrough = -1, activeRequest = -1;
  private static BlockPos activeOrigin;
  private static PreviewRenderScene scene;
  private static List<ProjectionPayloads.Entry> indexingEntries;
  private static ProjectionPayloads.Snapshot indexingHeader;
  private static CompletableFuture<PreviewRenderScene> indexing;
  private WorldProjection() {
  }
  public static void receive(ProjectionPayloads.Snapshot packet) {
    if (packet.request() < latest || packet.request() <= cancelledThrough) {
      return;
    }
    latest = packet.request();
    if (packet.start() == -1) {
      cancelledThrough = Math.max(cancelledThrough, packet.request());
      clear();
      return;
    }
    var mc = Minecraft.getInstance();
    if (mc.level == null
        || !packet.dimension().equals(mc.level.dimension().location())
        || outsideRange(packet.origin())) {
      ProjectionPayloads.stop(packet.request());
      cancelLocal();
      return;
    }
    if (packet.start() == 0) {
      ModularMachine.LOGGER.debug(
          "Projection {} incoming: {} entries, first packet {}",
          packet.request(),
          packet.total(),
          packet.entries().size());
      indexing = null;
      indexingEntries = null;
      indexingHeader = null;
      pending.clear();
      header = packet;
    }
    if (header == null
        || packet.request() != header.request()
        || packet.epoch() != header.epoch()
        || packet.total() != header.total()
        || !packet.dimension().equals(header.dimension())
        || !packet.machine().equals(header.machine())
        || !packet.origin().equals(header.origin())
        || pending.size() != packet.start()) {
      return;
    }
    pending.addAll(packet.entries());
    if (pending.size() == packet.total()) {
      ModularMachine.LOGGER.debug("Projection {} received completely", packet.request());
      indexingHeader = header;
      indexingEntries = List.copyOf(pending);
      pending.clear();
      advanceIndexing();
    }
  }

  private static void advanceIndexing() {
    if (indexingEntries == null) {
      return;
    }
    if (indexing == null) {
      var capturedHeader = indexingHeader;
      var capturedEntries = indexingEntries;
      indexing = PreviewMeshData.submitWork(() -> PreviewRenderScene.create(capturedHeader, capturedEntries));
    }
    if (indexing == null || !indexing.isDone()) {
      return;
    }
    try {
      if (indexingHeader.request() == latest && latest > cancelledThrough) {
        scene = indexing.join();
        activeOrigin = indexingHeader.origin();
        activeRequest = indexingHeader.request();
        CACHE.setScene(scene);
        ModularMachine.LOGGER
            .debug("Projection {} scene published: {} batches", activeRequest, scene.sections().size());
      }
    } catch (RuntimeException failure) {
      ModularMachine.LOGGER.warn("Projection scene indexing failed", failure);
      cancelLocal();
    }
    indexing = null;
    indexingEntries = null;
    indexingHeader = null;
    header = null;
  }
  public static void invalidateMeshes() {
    if (scene != null) {
      CACHE.setScene(scene);
    }
  }
  public static void sectionChanged(int x, int y, int z) {
    CACHE.sectionChanged(x, y, z);
  }
  public static void clear() {
    CACHE.close();
    scene = null;
    activeOrigin = null;
    activeRequest = -1;
    header = null;
    indexing = null;
    indexingEntries = null;
    indexingHeader = null;
    pending.clear();
  }
  private static boolean outsideRange(BlockPos origin) {
    var player = Minecraft.getInstance().player;
    int distance = MachineClientConfig.PROJECTION_CANCEL_DISTANCE.get();
    return player != null && player.distanceToSqr(Vec3.atCenterOf(origin)) > (double) distance * distance;
  }
  private static void cancelLocal() {
    cancelledThrough = Math.max(cancelledThrough, latest);
    if (Minecraft.getInstance().getConnection() != null) {
      if (activeRequest >= 0) {
        ProjectionPayloads.stop(activeRequest);
      }
      if (header != null && header.request() != activeRequest) {
        ProjectionPayloads.stop(header.request());
      }
    }
    clear();
  }
  @SubscribeEvent
  public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
    clear();
    latest = -1;
    cancelledThrough = -1;
  }
  @SubscribeEvent
  public static void tick(ClientTickEvent.Post event) {
    var mc = Minecraft.getInstance();
    if (mc.level == null
        || scene != null && !scene.dimension().equals(mc.level.dimension().location())
        || header != null && !header.dimension().equals(mc.level.dimension().location())
        || activeOrigin != null && outsideRange(activeOrigin)
        || header != null && outsideRange(header.origin())) {
      cancelLocal();
      return;
    }
    if (scene != null) {
      CACHE.tick();
    }
    advanceIndexing();
  }
  @SubscribeEvent
  public static void render(RenderLevelStageEvent event) {
    if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
      return;
    }
    // Client ticks pause with the integrated server; rendering continues.
    // Publish completed CPU work here as well, without reading world on workers.
    advanceIndexing();
    if (scene == null) {
      return;
    }
    CACHE.draw(event);
  }
}
