package com.lw.modularmachine.integration.jei;

import com.lw.modularmachine.client.preview.PreviewRenderScene;
import com.lw.modularmachine.client.preview.SectionMeshCache;
import com.lw.modularmachine.network.ProjectionPayloads;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Full-screen structure preview opened by the JEI details button. */
final class MachineDetailsScreen extends Screen {
  private static final int CANDIDATE_COLUMNS = 10;
  private static final int CANDIDATE_HORIZONTAL_SPACING = 20;
  private static final int CANDIDATE_VERTICAL_SPACING = 20;
  private static final int CANDIDATE_MAX_WIDTH = 250;
  private static final int REQUIRED_BLOCK_COLUMNS = 10;
  private static final int REQUIRED_BLOCK_HORIZONTAL_SPACING = 20;
  private static final int REQUIRED_BLOCK_VERTICAL_SPACING = 20;
  private static final int REQUIRED_BLOCK_SIDE_MARGIN = 20;
  private static final int REQUIRED_BLOCK_HEADER_HEIGHT = 38;
  private static final int REQUIRED_BLOCK_MAX_WIDTH = 250;
  private static final ResourceLocation SETTINGS_BACKGROUND = ResourceLocation
      .fromNamespaceAndPath("mmcece", "textures/gui/button_2.png");
  private static final ResourceLocation SETTINGS_ICON = ResourceLocation
      .fromNamespaceAndPath("mmcece", "textures/gui/seetting.png");
  private final ClientMachineJeiCatalog.Machine machine;
  private final SectionMeshCache cache = new SectionMeshCache();
  private PreviewRenderScene scene;
  private Matrix4f inverse;
  private float centerX, centerY, centerZ, yaw = -135F, pitch = 25F, zoom = 1F, panX, panY;
  private int step;
  private boolean cycle = true, dragging, settingsOpen;
  private double dragDistance;
  private int selected = -1;
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
    drawSettingsButton(graphics, left, top, panelWidth, mouseX, mouseY);
    if (settingsOpen)
      drawRequiredBlocks(graphics, left, top, panelWidth, panelHeight, mouseX, mouseY);
    drawSelectedCandidates(graphics, left, top, panelWidth, panelHeight, mouseX, mouseY);
  }

  private void drawHeader(GuiGraphics graphics, int left, int top) {
    ItemStack icon = MachineJeiPlugin.controller(machine.id());
    if (icon.isEmpty())
      icon = MachineJeiPlugin.factory(machine.id());
    if (!icon.isEmpty())
      graphics.renderItem(icon, left + 18, top + 12);
    graphics.drawString(font, title, left + 37, top + 16, 0xFF202020, false);
  }

  private void drawSettingsButton(GuiGraphics graphics, int left, int top, int panelWidth, int mouseX, int mouseY) {
    int x = left + panelWidth - 30;
    int y = top + 10;
    boolean hovered = mouseX >= x && mouseX < x + 20 && mouseY >= y && mouseY < y + 22;
    graphics.blit(SETTINGS_BACKGROUND, x, y, 0, 0, 20, 22, 20, 22);
    graphics.blit(SETTINGS_ICON, x + 2, y + 3, 0, 0, 16, 16, 16, 16);
    if (hovered) {
      graphics.renderOutline(x, y, 20, 22, 0xFF205A70);
      graphics.renderTooltip(font, Component.translatable("jei.mmcece.required_blocks"), mouseX, mouseY);
    }
  }

  private void drawRequiredBlocks(GuiGraphics graphics, int panelLeft, int panelTop, int panelWidth, int panelHeight, int mouseX, int mouseY) {
    var counts = requiredBlockCounts();
    int columns = REQUIRED_BLOCK_COLUMNS;
    int rows = Math.max(1, (counts.size() + columns - 1) / columns);
    int width = Math.min(
        panelWidth - 36,
        Math.min(REQUIRED_BLOCK_MAX_WIDTH, columns * REQUIRED_BLOCK_HORIZONTAL_SPACING + REQUIRED_BLOCK_SIDE_MARGIN));
    int height = Math.min(panelHeight - 76, REQUIRED_BLOCK_HEADER_HEIGHT + rows * REQUIRED_BLOCK_VERTICAL_SPACING);
    int left = panelLeft + panelWidth - width - 18;
    int top = panelTop + 58;
    graphics.fill(left, top, left + width, top + height, 0xE6D5D5D5);
    graphics.renderOutline(left, top, width, height, 0xFF555555);
    graphics
        .drawString(font, Component.translatable("jei.mmcece.required_blocks"), left + 8, top + 8, 0xFF202020, false);
    graphics.drawString(font, "X", left + width - 16, top + 8, 0xFF202020, false);
    int index = 0;
    for (var entry : counts.entrySet()) {
      int x = left + 8 + index % columns * REQUIRED_BLOCK_HORIZONTAL_SPACING;
      int y = top + 24 + index / columns * REQUIRED_BLOCK_VERTICAL_SPACING;
      var stack = new ItemStack(entry.getKey(), entry.getValue());
      graphics.renderItem(stack, x, y);
      graphics.renderItemDecorations(font, stack, x, y);
      if (mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18) {
        graphics.renderTooltip(font, stack, mouseX, mouseY);
      }
      index++;
    }
  }

  private LinkedHashMap<Item, Integer> requiredBlockCounts() {
    var counts = new LinkedHashMap<Item, Integer>();
    for (var part : machine.parts()) {
      var alternative = part.alternatives().stream().findFirst().orElse(null);
      if (alternative == null || alternative.getBlock().asItem().equals(net.minecraft.world.item.Items.AIR)) {
        continue;
      }
      counts.merge(alternative.getBlock().asItem(), 1, Integer::sum);
    }
    return counts;
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
      inverse = new Matrix4f(graphics.pose().last().pose()).invert();
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

  private void drawSelectedCandidates(GuiGraphics graphics, int panelLeft, int panelTop, int panelWidth, int panelHeight, int mouseX, int mouseY) {
    if (selected < 0 || selected >= machine.parts().size()) {
      return;
    }
    var alternatives = machine.parts().get(selected).alternatives();
    if (alternatives.isEmpty()) {
      return;
    }
    int left = candidateLeft(panelLeft);
    int top = candidateTop(panelTop);
    int columns = CANDIDATE_COLUMNS;
    int rows = (alternatives.size() + columns - 1) / columns;
    int width = Math.min(panelWidth - 36, Math.min(CANDIDATE_MAX_WIDTH, 16 + columns * CANDIDATE_HORIZONTAL_SPACING));
    int height = Math.min(panelHeight - 76, 38 + rows * CANDIDATE_VERTICAL_SPACING);
    graphics.fill(left, top, left + width, top + height, 0xE6D5D5D5);
    graphics.renderOutline(left, top, width, height, 0xFF555555);
    graphics.drawString(font, Component.translatable("jei.mmcece.candidates"), left + 8, top + 8, 0xFF202020, false);
    graphics.drawString(font, "X", left + width - 16, top + 8, 0xFF202020, false);
    for (int index = 0; index < alternatives.size(); index++) {
      var stack = new ItemStack(alternatives.get(index).getBlock());
      int x = left + 8 + index % columns * CANDIDATE_HORIZONTAL_SPACING;
      int y = top + 24 + index / columns * CANDIDATE_VERTICAL_SPACING;
      graphics.renderItem(stack, x, y);
      graphics.renderItemDecorations(font, stack, x, y);
      if (mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18) {
        graphics.renderTooltip(font, stack, mouseX, mouseY);
      }
    }
  }

  @Override
  public boolean mouseClicked(double x, double y, int button) {
    int panelLeft = Math.max(12, (width - Math.min(760, width - 24)) / 2);
    int panelTop = Math.max(12, (height - Math.min(900, height - 24)) / 2);
    int panelWidth = Math.min(760, width - panelLeft * 2);
    int settingsX = panelLeft + panelWidth - 30;
    int settingsY = panelTop + 10;
    if (button == 0 && x >= settingsX && x < settingsX + 20 && y >= settingsY && y < settingsY + 22) {
      settingsOpen = !settingsOpen;
      selected = -1;
      return true;
    }
    if (button == 0
        && settingsOpen
        && x >= panelLeft + panelWidth - Math.min(panelWidth - 36, REQUIRED_BLOCK_MAX_WIDTH) - 18
        && x < panelLeft + panelWidth - 18
        && y >= panelTop + 58
        && y < panelTop + 58 + Math.min(500, height - panelTop - 76)) {
      if (x >= panelLeft + panelWidth - 18 - 24 && y < panelTop + 82) {
        settingsOpen = false;
      }
      return true;
    }
    if (button == 0
        && selected >= 0
        && x >= candidateLeft(Math.max(12, (width - Math.min(760, width - 24)) / 2)) + 180
        && x < candidateLeft(Math.max(12, (width - Math.min(760, width - 24)) / 2)) + 220
        && y >= candidateTop(Math.max(12, (height - Math.min(900, height - 24)) / 2))
        && y < candidateTop(Math.max(12, (height - Math.min(900, height - 24)) / 2)) + 24) {
      selected = -1;
      return true;
    }
    if (button == 0 && selected >= 0 && candidateBounds(x, y)) {
      return true;
    }
    int left = Math.max(12, (width - Math.min(760, width - 24)) / 2);
    int top = Math.max(12, (height - Math.min(900, height - 24)) / 2);
    int panelHeight = Math.min(900, height - top * 2);
    if (y >= top + 48 && y < top + panelHeight - 20) {
      dragging = true;
      dragDistance = 0;
      return true;
    }
    return super.mouseClicked(x, y, button);
  }

  @Override
  public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
    if (!dragging)
      return super.mouseDragged(x, y, button, dx, dy);
    if (button == 0) {
      dragDistance += Math.abs(dx) + Math.abs(dy);
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
    if (dragging && button == 0 && dragDistance <= 4 && inverse != null && scene != null) {
      var from = new Vector3f((float) x, (float) y, 10000);
      var to = new Vector3f((float) x, (float) y, -10000);
      inverse.transformPosition(from);
      inverse.transformPosition(to);
      var start = new Vec3(from.x, from.y, from.z);
      var end = new Vec3(to.x, to.y, to.z);
      double nearest = Double.MAX_VALUE;
      selected = -1;
      for (var section : scene.sections()) {
        if (section.bounds().clip(start, end).isEmpty()) {
          continue;
        }
        for (var entry : section.entries()) {
          var hit = new AABB(entry.position()).clip(start, end);
          if (hit.isPresent() && hit.get().distanceToSqr(start) < nearest) {
            nearest = hit.get().distanceToSqr(start);
            selected = scene.indexAt(entry.position());
          }
        }
      }
    }
    dragging = false;
    return true;
  }

  private boolean candidateBounds(double x, double y) {
    int panelLeft = Math.max(12, (width - Math.min(760, width - 24)) / 2);
    int panelTop = Math.max(12, (height - Math.min(900, height - 24)) / 2);
    return x >= candidateLeft(panelLeft)
        && x < candidateLeft(panelLeft) + 212
        && y >= candidateTop(panelTop)
        && y < candidateTop(panelTop) + 500;
  }

  private static int candidateLeft(int panelLeft) {
    return panelLeft + 18;
  }

  private static int candidateTop(int panelTop) {
    return panelTop + 58;
  }

  @Override
  public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    if (keyCode == GLFW.GLFW_KEY_X && settingsOpen) {
      settingsOpen = false;
      return true;
    }
    if (keyCode == GLFW.GLFW_KEY_X && selected >= 0) {
      selected = -1;
      return true;
    }
    return super.keyPressed(keyCode, scanCode, modifiers);
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
