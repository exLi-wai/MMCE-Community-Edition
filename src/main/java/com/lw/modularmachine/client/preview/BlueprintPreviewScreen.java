package com.lw.modularmachine.client.preview;

import com.lw.modularmachine.integration.jei.ClientMachineJeiCatalog;
import com.lw.modularmachine.machine.blueprint.BlueprintBindings;
import com.lw.modularmachine.network.ProjectionPayloads;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Original blueprint interaction and 184x220 panel using the shared bounded
 * projection mesh cache.
 */
public final class BlueprintPreviewScreen extends Screen {
  private static final ResourceLocation TEXTURE = ResourceLocation
      .fromNamespaceAndPath("mmcece", "textures/gui/guiblueprint_new.png");
  private final ResourceLocation machine;
  private final InteractionHand hand;
  private final SectionMeshCache cache = new SectionMeshCache();
  private List<ProjectionPayloads.Entry> entries = List.of();
  private List<ItemStack> materials = List.of();
  private final LinkedHashMap<Item, Integer> materialCounts = new LinkedHashMap<>();
  private int materialStep;
  private boolean materialsInitialized;
  private PreviewRenderScene scene;
  private Matrix4f inverse;
  private float centerX, centerY, centerZ, baseScale = 1, yaw = -135, pitch = 25, zoom = 1, panX, panY;
  private int left, top, minY, maxY, layer, step, page, selected = -1;
  private boolean layers, cycle = true, menu = true, dragging;
  private double dragDistance;
  private long lastCycle;

  public BlueprintPreviewScreen(ResourceLocation machine, InteractionHand hand) {
    super(Component.translatable(ClientMachineJeiCatalog.translation(machine)));
    this.machine = machine;
    this.hand = hand;
  }
  @Override
  protected void init() {
    left = (width - 184) / 2;
    top = (height - 220) / 2;
    if (scene != null || minecraft.level == null) {
      return;
    }
    var definition = ClientMachineJeiCatalog.machines().get(machine);
    if (definition == null) {
      return;
    }
    entries = definition.parts().stream()
        .map(part -> new ProjectionPayloads.Entry(part.offset(), part.alternatives(), List.of())).toList();
    float minX = entries.stream().mapToInt(entry -> entry.position().getX()).min().orElse(0);
    float maxX = entries.stream().mapToInt(entry -> entry.position().getX()).max().orElse(0);
    float minZ = entries.stream().mapToInt(entry -> entry.position().getZ()).min().orElse(0);
    float maxZ = entries.stream().mapToInt(entry -> entry.position().getZ()).max().orElse(0);
    minY = entries.stream().mapToInt(entry -> entry.position().getY()).min().orElse(0);
    maxY = entries.stream().mapToInt(entry -> entry.position().getY()).max().orElse(0);
    layer = minY;
    centerX = (minX + maxX + 1) / 2;
    centerY = (minY + maxY + 1) / 2F;
    centerZ = (minZ + maxZ + 1) / 2;
    baseScale = Math
        .max(.01F, Math.min(24F, 95F / Math.max(maxX - minX + 1, Math.max(maxY - minY + 1, maxZ - minZ + 1))));
    scene = PreviewRenderScene.create(0, minecraft.level.dimension().location(), machine, entries);
    cache.setPreviewScene(scene);
    updateMaterials();
    lastCycle = Util.getMillis();
  }
  private BlockState shown(ProjectionPayloads.Entry entry) {
    return entry.states().get(Math.floorMod(step, entry.states().size()));
  }
  private void updateMaterials() {
    var changed = materialsInitialized ? scene.animated() : entries;
    for (var entry : changed) {
      if (entry.states().isEmpty()) {
        continue;
      }
      if (materialsInitialized) {
        var previous = entry.states().get(Math.floorMod(materialStep, entry.states().size())).getBlock().asItem();
        if (previous != Items.AIR) {
          materialCounts.computeIfPresent(previous, (item, count) -> count == 1 ? null : count - 1);
        }
      }
      var item = shown(entry).getBlock().asItem();
      if (item != Items.AIR) {
        materialCounts.merge(item, 1, Integer::sum);
      }
    }
    materialStep = step;
    materialsInitialized = true;
    materials = materialCounts.entrySet().stream().sorted(Entry.<Item, Integer>comparingByValue().reversed())
        .map(entry -> new ItemStack(entry.getKey(), entry.getValue())).toList();
    page = Math.min(page, Math.max(0, (materials.size() - 1) / 18));
  }
  @Override
  public void tick() {
    if (minecraft.player == null || minecraft.level == null) {
      onClose();
      return;
    }
    if (scene == null) {
      init();
    }
    var held = BlueprintBindings.binding(minecraft.player.getItemInHand(hand));
    if (held == null || !held.machine().equals(machine)) {
      onClose();
    }
  }
  public void invalidateMeshes() {
    if (scene != null) {
      cache.setPreviewScene(scene);
    }
  }
  @Override
  public boolean isPauseScreen() {
    return false;
  }
  @Override
  public void removed() {
    cache.close();
    super.removed();
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    super.render(graphics, mouseX, mouseY, partialTick);
    graphics.blit(TEXTURE, left, top, 0, 0, 184, 220);
    drawMachineTitle(graphics);
    if (scene == null) {
      graphics.drawString(
          font,
          Component.translatable("message.mmcece.blueprint.invalid"),
          left + 6,
          top + 30,
          0xFFFF5555,
          true);
      return;
    }
    if (cycle && !scene.animated().isEmpty() && Util.getMillis() - lastCycle >= 1500) {
      step++;
      lastCycle = Util.getMillis();
      updateMaterials();
    }
    cache.setPreviewState(layers ? layer : Integer.MIN_VALUE, step);
    graphics.flush();
    graphics.enableScissor(left + 6, top + 26, left + 178, top + 176);
    graphics.pose().pushPose();
    try {
      graphics.pose().translate(left + 92 + panX, top + 99 + panY, 150);
      graphics.pose().scale(baseScale * zoom, -baseScale * zoom, baseScale * zoom);
      graphics.pose().mulPose(
          new Quaternionf()
              .rotationXYZ((float) Math.toRadians(layers ? 90 : pitch), (float) Math.toRadians(layers ? 0 : yaw), 0));
      graphics.pose().translate(-centerX, layers ? -layer - .5F : -centerY, -centerZ);
      inverse = new Matrix4f(graphics.pose().last().pose()).invert();
      cache.drawPreview(
          new Matrix4f(RenderSystem.getModelViewMatrix()).mul(graphics.pose().last().pose()),
          RenderSystem.getProjectionMatrix());
    } finally {
      graphics.pose().popPose();
      graphics.disableScissor();
    }
    graphics.drawString(
        font,
        layers ? Component.translatable("jei.mmcece.legacy.layer", layer) : Component.literal("3D"),
        left + 8,
        top + 28,
        0xFFFFFFFF,
        true);
    button(graphics, 165, 161, 15, menu, mouseX, mouseY);
    if (menu) {
      button(graphics, 150, 161, 30, layers, mouseX, mouseY);
      button(graphics, 135, 161, 105, cycle, mouseX, mouseY);
    }
    button(graphics, 150, 28, 229, false, mouseX, mouseY);
    button(graphics, 165, 28, 214, false, mouseX, mouseY);
    for (int index = 0; index < 18 && page * 18 + index < materials.size(); index++) {
      var stack = materials.get(page * 18 + index);
      int x = left + 6 + index % 9 * 19, y = top + 180 + index / 9 * 18;
      graphics.renderItem(stack, x, y);
      graphics.renderItemDecorations(font, stack, x, y);
    }
    if (selected >= 0) {
      var alternatives = entries.get(selected).states();
      for (int index = 0; index < Math.min(7, alternatives.size()); index++) {
        graphics.renderItem(new ItemStack(alternatives.get(index).getBlock()), left + 8, top + 46 + index * 16);
      }
    }
    var tooltip = tooltip(mouseX - left, mouseY - top);
    if (!tooltip.isEmpty()) {
      graphics.renderTooltip(font, tooltip, Optional.empty(), mouseX, mouseY);
    }
  }

  /**
   * Mirrors the legacy StructurePreviewTitle: a 36px prefix cell followed by a
   * 136px centered machine-name cell.
   */
  private void drawMachineTitle(GuiGraphics graphics) {
    var lines = font.split(title, 136);
    if (lines.isEmpty()) {
      return;
    }
    var line = lines.getFirst();
    int titleWidth = font.width(line);
    int x = left + 43 + Math.max(0, (136 - titleWidth) / 2);
    // Legacy MultiLineLabel centers its 10px line inside the 18px title row.
    int y = top + 5 + (18 - 10) / 2;
    graphics.drawString(font, line, x, y, 0xFFFFFFFF, true);
  }
  private void button(GuiGraphics graphics, int x, int y, int v, boolean selected, double mouseX, double mouseY) {
    boolean over = mouseX >= left + x && mouseX < left + x + 13 && mouseY >= top + y && mouseY < top + y + 13;
    graphics.blit(TEXTURE, left + x, top + y, 184 + (selected ? 30 : over ? 15 : 0), v, 13, 13);
  }
  private List<Component> tooltip(double x, double y) {
    if (y >= 180 && y < 216 && x >= 6 && x < 178) {
      int index = page * 18 + (int) (x - 6) / 19 + (int) (y - 180) / 18 * 9;
      if (index < materials.size()) {
        return Screen.getTooltipFromItem(minecraft, materials.get(index));
      }
    }
    if (y >= 161 && y < 174) {
      if (x >= 165 && x < 178) {
        return List.of(Component.translatable("gui.preview.button.menu.tip"));
      }
      if (menu && x >= 150 && x < 163) {
        return List.of(
            Component.translatable(
                layers ? "gui.preview.button.toggle_3d_render.tip" : "gui.preview.button.toggle_layer_render.tip"));
      }
      if (menu && x >= 135 && x < 148) {
        return List.of(
            Component.translatable(
                cycle ? "gui.preview.button.disable_cycle_replaceable_blocks.tip" : "gui.preview.button.enable_cycle_replaceable_blocks.tip.0"));
      }
    }
    if (y >= 28 && y < 41 && x >= 150 && x < 163) {
      return List.of(Component.translatable("gui.preview.button.reset_center.tip"));
    }
    if (y >= 28 && y < 41 && x >= 165 && x < 178) {
      return List.of(Component.literal(machine.toString()), Component.translatable("gui.mmcece.blueprint.controls"));
    }
    return List.of();
  }
  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    double x = mouseX - left, y = mouseY - top;
    if (y >= 161 && y < 174 && button == 0) {
      if (x >= 165 && x < 178) {
        menu = !menu;
        return true;
      }
      if (menu && x >= 150 && x < 163) {
        layers = !layers;
        return true;
      }
      if (menu && x >= 135 && x < 148) {
        cycle = !cycle;
        lastCycle = Util.getMillis();
        return true;
      }
    }
    if (x >= 150 && x < 163 && y >= 28 && y < 41 && button == 0) {
      yaw = -135;
      pitch = 25;
      panX = 0;
      panY = 0;
      zoom = 1;
      return true;
    }
    if (x >= 6 && x < 178 && y >= 26 && y < 176) {
      dragging = true;
      dragDistance = 0;
      return true;
    }
    return super.mouseClicked(mouseX, mouseY, button);
  }
  @Override
  public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
    if (!dragging) {
      return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }
    dragDistance += Math.abs(dx) + Math.abs(dy);
    if (button == 0) {
      yaw += dx * .25F;
      pitch = Math.max(-89.9F, Math.min(89.9F, pitch + (float) dy * .25F));
    } else {
      panX += dx;
      panY += dy;
    }
    return true;
  }
  @Override
  public boolean mouseReleased(double x, double y, int button) {
    if (!dragging) {
      return super.mouseReleased(x, y, button);
    }
    dragging = false;
    if (button == 0 && dragDistance <= 4 && inverse != null) {
      var from = new Vector3f((float) x, (float) y, 10000);
      inverse.transformPosition(from);
      var to = new Vector3f((float) x, (float) y, -10000);
      inverse.transformPosition(to);
      var start = new Vec3(from.x, from.y, from.z);
      var end = new Vec3(to.x, to.y, to.z);
      double nearest = Double.MAX_VALUE;
      selected = -1;
      for (var section : scene.sections()) {
        if (layers && (layer < section.bounds().minY || layer >= section.bounds().maxY)
            || section.bounds().clip(start, end).isEmpty()) {
          continue;
        }
        for (var entry : section.entries()) {
          if (layers && entry.position().getY() != layer) {
            continue;
          }
          var hit = new AABB(entry.position()).clip(start, end);
          if (hit.isPresent() && hit.get().distanceToSqr(start) < nearest) {
            nearest = hit.get().distanceToSqr(start);
            selected = scene.indexAt(entry.position());
          }
        }
      }
    }
    return true;
  }
  @Override
  public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
    x -= left;
    y -= top;
    if (x >= 5 && x < 179 && y >= 179 && y < 216) {
      page = Math.max(0, Math.min((materials.size() - 1) / 18, page - (int) Math.signum(scrollY)));
      return true;
    }
    if (x >= 6 && x < 178 && y >= 26 && y < 176) {
      if (layers) {
        layer = Math.max(minY, Math.min(maxY, layer + (int) Math.signum(scrollY)));
      } else {
        zoom = Math.max(.1F, Math.min(8, zoom * (float) Math.pow(1.1, scrollY)));
      }
      return true;
    }
    return false;
  }
}
