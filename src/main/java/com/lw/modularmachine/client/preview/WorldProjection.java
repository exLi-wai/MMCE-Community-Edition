package com.lw.modularmachine.client.preview;

import com.lw.modularmachine.network.ProjectionPayloads;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent.Post;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

@EventBusSubscriber(modid = "mmcece", value = Dist.CLIENT)
public final class WorldProjection {
  private static final SectionMeshCache CACHE = new SectionMeshCache();
  private static final ArrayList<ProjectionPayloads.Entry> pending = new ArrayList<>();
  private static ProjectionPayloads.Snapshot header;
  private static long latest = -1;
  private static PreviewRenderScene scene;
  private static List<Component> materials = List.of();
  private WorldProjection() {
  }
  public static void receive(ProjectionPayloads.Snapshot packet) {
    if (packet.request() < latest) {
      return;
    }
    latest = packet.request();
    if (packet.start() == -1) {
      clear();
      return;
    }
    if (packet.start() == 0) {
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
      var counts = new LinkedHashMap<Item, Integer>();
      for (var entry : pending) {
        if (!entry.states().isEmpty()) {
          var item = entry.states().getFirst().getBlock().asItem();
          if (item != Items.AIR) {
            counts.merge(item, 1, Integer::sum);
          }
        }
      }
      var lines = new ArrayList<Component>();
      lines.add(Component.translatable("hud.mmcece.projection.title", header.machine().toString()));
      lines.add(Component.translatable("hud.mmcece.projection.legend"));
      lines.add(Component.translatable("hud.mmcece.projection.materials"));
      counts.entrySet().stream().limit(8).forEach(
          entry -> lines.add(
              Component
                  .translatable("hud.mmcece.projection.material", entry.getKey().getDescription(), entry.getValue())));
      materials = List.copyOf(lines);
      scene = PreviewRenderScene.create(header, pending);
      CACHE.setScene(scene);
      pending.clear();
      header = null;
    }
  }
  public static void invalidateMeshes() {
    if (scene != null) {
      CACHE.setScene(scene);
    }
  }
  public static void clear() {
    CACHE.close();
    scene = null;
    materials = List.of();
    header = null;
    pending.clear();
  }
  @SubscribeEvent
  public static void hud(Post event) {
    var mc = Minecraft.getInstance();
    if (scene == null || mc.options.hideGui || mc.screen != null || mc.player == null) {
      return;
    }
    var graphics = event.getGuiGraphics();
    int y = 8;
    for (var line : materials) {
      for (var wrapped : mc.font.split(line, Math.min(280, graphics.guiWidth() - 16))) {
        if (y > graphics.guiHeight() - 20) {
          return;
        }
        graphics.fill(5, y - 1, 11 + mc.font.width(wrapped), y + 10, 0x90000000);
        graphics.drawString(mc.font, wrapped, 8, y, 0xFFBFEFFF);
        y += 11;
      }
    }
  }
  @SubscribeEvent
  public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
    clear();
    latest = -1;
  }
  @SubscribeEvent
  public static void tick(ClientTickEvent.Post event) {
    var mc = Minecraft.getInstance();
    if (mc.level == null || scene != null && !scene.dimension().equals(mc.level.dimension().location())) {
      clear();
      return;
    }
    if (scene != null) {
      CACHE.tick();
    }
  }
  @SubscribeEvent
  public static void render(RenderLevelStageEvent event) {
    if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || scene == null) {
      return;
    }
    CACHE.upload();
    CACHE.draw(event);
  }
}
