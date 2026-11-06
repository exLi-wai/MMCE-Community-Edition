package com.lw.modularmachine.integration.jei;

import com.lw.modularmachine.client.preview.PreviewRenderScene;
import com.lw.modularmachine.client.preview.SectionMeshCache;
import com.lw.modularmachine.network.ProjectionPayloads;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/** Full-screen structure preview opened by the JEI details button. */
final class MachineDetailsScreen extends Screen {
  private final ClientMachineJeiCatalog.Machine machine;
  private final SectionMeshCache cache = new SectionMeshCache();
  private PreviewRenderScene scene;
  private float centerX, centerY, centerZ, yaw = -135F, pitch = 25F, zoom = 1F, panX, panY;
  private int step;
  private boolean cycle = true, dragging;
  private long lastCycle;

  MachineDetailsScreen(ClientMachineJeiCatalog.Machine machine) {
    super(Component.translatable("jei.mmcece.details.title", Component.translatable(machine.translation())));
    this.machine = machine;
  }

  @Override
  protected void init() {
    var entries = machine.parts().stream()
        .map(part -> new ProjectionPayloads.Entry(part.offset(), part.alternatives(), List.of())).toList();
    int minX = entries.stream().mapToInt(entry -> entry.position().getX()).min().orElse(0);
    int maxX = entries.stream().mapToInt(entry -> entry.position().getX()).max().orElse(0);
    int minY = entries.stream().mapToInt(entry -> entry.position().getY()).min().orElse(0);
    int maxY = entries.stream().mapToInt(entry -> entry.position().getY()).max().orElse(0);
    int minZ = entries.stream().mapToInt(entry -> entry.position().getZ()).min().orElse(0);
    int maxZ = entries.stream().mapToInt(entry -> entry.position().getZ()).max().orElse(0);
    centerX = (minX + maxX + 1) / 2F;
    centerY = (minY + maxY + 1) / 2F;
    centerZ = (minZ + maxZ + 1) / 2F;
    if (minecraft.level != null) {
      scene = PreviewRenderScene.create(0, minecraft.level.dimension().location(), machine.id(), entries);
      cache.setPreviewScene(scene);
      cache.setPreviewState(Integer.MIN_VALUE, step);
    }
    lastCycle = Util.getMillis();
  }

  @Override
  public void tick() {
    if (minecraft.level == null) {
      onClose();
      return;
    }
    if (cycle && scene != null && !scene.animated().isEmpty() && Util.getMillis() - lastCycle >= 1500) {
      step++;
      lastCycle = Util.getMillis();
      cache.setPreviewState(Integer.MIN_VALUE, step);
    }
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    super.render(graphics, mouseX, mouseY, partialTick);
    int left = Math.max(12, (width - Math.min(760, width - 24)) / 2);
    int top = Math.max(12, (height - Math.min(900, height - 24)) / 2);
    int panelWidth = Math.min(760, width - left * 2);
    int panelHeight = Math.min(900, height - top * 2);
    graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xFFC6C6C6);
    graphics.renderOutline(left, top, panelWidth, panelHeight, 0xFF555555);
    drawHeader(graphics, left, top);
    if (scene != null)
      drawScene(graphics, left, top, panelWidth, panelHeight);
  }

  private void drawHeader(GuiGraphics graphics, int left, int top) {
    ItemStack icon = MachineJeiPlugin.controller(machine.id());
    if (icon.isEmpty())
      icon = MachineJeiPlugin.factory(machine.id());
    if (!icon.isEmpty())
      graphics.renderItem(icon, left + 18, top + 12);
    graphics.drawString(font, title, left + 37, top + 16, 0xFF202020, false);
  }

  private void drawScene(GuiGraphics graphics, int panelLeft, int panelTop, int panelWidth, int panelHeight) {
    int left = panelLeft + 8, top = panelTop + 48, right = panelLeft + panelWidth - 8,
        bottom = panelTop + panelHeight - 42;
    float available = Math.min(right - left, bottom - top);
    float scale = Math.max(.01F, Math.min(24F, available * .55F / structureExtent())) * zoom;
    graphics.flush();
    graphics.enableScissor(left, top, right, bottom);
    graphics.pose().pushPose();
    try {
      graphics.pose().translate((left + right) / 2F + panX, (top + bottom) / 2F + panY, 150);
      graphics.pose().scale(scale, -scale, scale);
      graphics.pose()
          .mulPose(new Quaternionf().rotationXYZ((float) Math.toRadians(pitch), (float) Math.toRadians(yaw), 0));
      graphics.pose().translate(-centerX, -centerY, -centerZ);
      cache.drawPreview(
          new Matrix4f(RenderSystem.getModelViewMatrix()).mul(graphics.pose().last().pose()),
          RenderSystem.getProjectionMatrix());
    } finally {
      graphics.pose().popPose();
      graphics.disableScissor();
    }
  }

  private float structureExtent() {
    float minX = machine.parts().stream().mapToInt(part -> part.offset().getX()).min().orElse(0);
    float maxX = machine.parts().stream().mapToInt(part -> part.offset().getX()).max().orElse(0);
    float minY = machine.parts().stream().mapToInt(part -> part.offset().getY()).min().orElse(0);
    float maxY = machine.parts().stream().mapToInt(part -> part.offset().getY()).max().orElse(0);
    float minZ = machine.parts().stream().mapToInt(part -> part.offset().getZ()).min().orElse(0);
    float maxZ = machine.parts().stream().mapToInt(part -> part.offset().getZ()).max().orElse(0);
    return Math.max(maxX - minX + 1, Math.max(maxY - minY + 1, maxZ - minZ + 1));
  }

  @Override
  public boolean mouseClicked(double x, double y, int button) {
    int left = Math.max(12, (width - Math.min(760, width - 24)) / 2);
    int top = Math.max(12, (height - Math.min(900, height - 24)) / 2);
    int panelWidth = Math.min(760, width - left * 2);
    int panelHeight = Math.min(900, height - top * 2);
    if (y >= top + 48 && y < top + panelHeight - 20) {
      dragging = true;
      return true;
    }
    return super.mouseClicked(x, y, button);
  }

  @Override
  public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
    if (!dragging)
      return super.mouseDragged(x, y, button, dx, dy);
    if (button == 0) {
      yaw += dx * .25F;
      pitch = Math.max(-89F, Math.min(89F, pitch + (float) dy * .25F));
    } else {
      panX += dx;
      panY += dy;
    }
    return true;
  }

  @Override
  public boolean mouseReleased(double x, double y, int button) {
    dragging = false;
    return true;
  }

  @Override
  public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
    if (y < 70 || y >= height - 26)
      return false;
    zoom = Math.max(.1F, Math.min(8F, zoom * (float) Math.pow(1.1, scrollY)));
    return true;
  }

  @Override
  public void removed() {
    cache.close();
    super.removed();
  }
}
