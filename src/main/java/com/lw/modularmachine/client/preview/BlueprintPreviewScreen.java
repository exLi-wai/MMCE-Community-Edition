package com.lw.modularmachine.client.preview;

import com.lw.modularmachine.api.client.preview.PreviewCamera;
import com.lw.modularmachine.api.client.preview.PreviewRenderer;
import com.lw.modularmachine.api.client.preview.PreviewSceneData;
import com.lw.modularmachine.api.client.preview.PreviewViewport;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.integration.jei.ClientMachineJeiCatalog;
import com.lw.modularmachine.item.MachineBlueprintItem;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.block.FactoryControllerBlock;
import com.lw.modularmachine.network.BlueprintPreviewPayloads;
import com.lw.modularmachine.network.ProjectionPayloads;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Original blueprint interaction and 184x220 panel using the shared preview
 * renderer.
 */
public final class BlueprintPreviewScreen extends Screen {
  private static final ResourceLocation TEXTURE = ResourceLocation
      .fromNamespaceAndPath("mmcece", "textures/gui/guiblueprint_new.png");
  private final ResourceLocation machine;
  private final InteractionHand hand;
  private static long nextRequest;
  private long previewRequest = -1;
  private BlueprintPreviewPayloads.Snapshot previewHeader;
  private final ArrayList<ProjectionPayloads.Entry> incoming = new ArrayList<>();
  private ClientMachineJeiCatalog.Machine catalogDefinition;
  private boolean awaitingPreview, previewFailed, dynamic;
  private int dynamicSize = -1, dynamicMinimum, dynamicMaximum;
  private final PreviewRenderer renderer = PreviewRenderer.create();
  private final PreviewCamera camera = new PreviewCamera(89.9F);
  private List<PreviewSceneData.Entry> entries = List.of();
  private List<PreviewSceneData.Entry> animated = List.of();
  private List<ItemStack> materials = List.of();
  private final LinkedHashMap<Item, Integer> materialCounts = new LinkedHashMap<>();
  private int materialStep;
  private boolean materialsInitialized;
  private PreviewSceneData scene;
  private float baseScale = 1;
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
    catalogDefinition = definition;
    entries = definition.parts().stream()
        .map(part -> new PreviewSceneData.Entry(part.offset(), part.alternatives(), List.of())).toList();
    applyScene(definition.color());
    if (previewRequest < 0) {
      requestPreview(-1);
    }
  }

  private void applyScene(int color) {
    animated = entries.stream().filter(entry -> entry.states().size() > 1).toList();
    minY = entries.stream().mapToInt(entry -> entry.position().getY()).min().orElse(0);
    maxY = entries.stream().mapToInt(entry -> entry.position().getY()).max().orElse(0);
    layer = minY;
    selected = -1;
    materialCounts.clear();
    materialsInitialized = false;
    scene = new PreviewSceneData(color, entries);
    baseScale = Math.max(.01F, Math.min(24F, 95F / scene.extent()));
    renderer.setScene(scene);
    updateMaterials();
    lastCycle = Util.getMillis();
  }

  private void requestPreview(int size) {
    previewRequest = nextRequest = Math.incrementExact(nextRequest);
    previewHeader = null;
    incoming.clear();
    awaitingPreview = true;
    previewFailed = false;
    BlueprintPreviewPayloads.request(previewRequest, hand, machine, size);
  }

  public void receive(BlueprintPreviewPayloads.Snapshot snapshot) {
    var packet = snapshot.preview();
    if (!awaitingPreview
        || packet.request() != previewRequest
        || !packet.machine().equals(machine)
        || minecraft.level == null
        || !packet.dimension().equals(minecraft.level.dimension().location())) {
      return;
    }
    if (packet.start() == -1) {
      awaitingPreview = false;
      previewFailed = true;
      incoming.clear();
      if (snapshot.dynamic()) {
        dynamic = true;
        dynamicSize = snapshot.size();
        dynamicMinimum = snapshot.minimum();
        dynamicMaximum = snapshot.maximum();
        if (scene == null) {
          entries = List.of();
          applyScene(snapshot.color());
        }
      }
      return;
    }
    if (packet.start() == 0) {
      previewHeader = snapshot;
      incoming.clear();
    }
    if (previewHeader == null
        || previewHeader.preview().epoch() != packet.epoch()
        || previewHeader.preview().total() != packet.total()
        || previewHeader.size() != snapshot.size()
        || previewHeader.minimum() != snapshot.minimum()
        || previewHeader.maximum() != snapshot.maximum()
        || previewHeader.dynamic() != snapshot.dynamic()
        || previewHeader.color() != snapshot.color()
        || previewHeader.orientation() != snapshot.orientation()
        || incoming.size() != packet.start()) {
      return;
    }
    incoming.addAll(packet.entries());
    if (incoming.size() != packet.total()) {
      return;
    }
    var complete = new ArrayList<PreviewSceneData.Entry>();
    if (catalogDefinition != null) {
      catalogDefinition.parts().stream().filter(part -> part.offset().equals(BlockPos.ZERO)).findFirst().ifPresent(
          part -> complete.add(
              new PreviewSceneData.Entry(
                  part.offset(),
                  part.alternatives().stream().map(state -> controllerOrientation(state, snapshot.orientation()))
                      .toList(),
                  List.of())));
    }
    incoming.stream().map(part -> new PreviewSceneData.Entry(part.position(), part.states(), part.properties()))
        .forEach(complete::add);
    entries = List.copyOf(complete);
    dynamic = snapshot.dynamic();
    dynamicSize = snapshot.size();
    dynamicMinimum = snapshot.minimum();
    dynamicMaximum = snapshot.maximum();
    incoming.clear();
    awaitingPreview = false;
    previewHeader = null;
    applyScene(snapshot.color());
  }

  private static BlockState controllerOrientation(BlockState state, MachineOrientation orientation) {
    if (state.getBlock() instanceof MachineControllerBlock) {
      return MachineControllerBlock.withOrientation(state, orientation);
    }
    if (state.getBlock() instanceof FactoryControllerBlock) {
      return FactoryControllerBlock.withOrientation(state, orientation);
    }
    return state;
  }
  private BlockState shown(PreviewSceneData.Entry entry) {
    return entry.states().get(Math.floorMod(step, entry.states().size()));
  }
  private void updateMaterials() {
    var changed = materialsInitialized ? animated : entries;
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
    var currentDefinition = ClientMachineJeiCatalog.machines().get(machine);
    if (catalogDefinition != null && currentDefinition != catalogDefinition) {
      catalogDefinition = currentDefinition;
      scene = null;
      renderer.close();
      requestPreview(-1);
    }
    if (scene == null && !previewFailed && !awaitingPreview) {
      init();
    }
    var held = MachineBlueprintItem.binding(minecraft.player.getItemInHand(hand));
    if (held == null || !held.machine().equals(machine)) {
      onClose();
    }
  }
  public void invalidateMeshes() {
    if (scene != null) {
      renderer.invalidate();
    }
  }
  @Override
  public boolean isPauseScreen() {
    return false;
  }
  @Override
  public void removed() {
    awaitingPreview = false;
    incoming.clear();
    renderer.close();
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
          Component.translatable(
              awaitingPreview ? "gui.mmcece.blueprint.dynamic_waiting" : "message.mmcece.blueprint.invalid"),
          left + 6,
          top + 30,
          0xFFFF5555,
          true);
      return;
    }
    if (cycle && !animated.isEmpty() && Util.getMillis() - lastCycle >= 1500) {
      step++;
      lastCycle = Util.getMillis();
      updateMaterials();
    }
    renderer.render(
        graphics,
        new PreviewViewport(left + 6, top + 26, 172, 150, baseScale, left + 92, top + 99),
        camera,
        layers ? layer : Integer.MIN_VALUE,
        step);
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
      if (dynamic) {
        button(graphics, 120, 161, 60, false, mouseX, mouseY);
        button(graphics, 105, 161, 75, false, mouseX, mouseY);
      }
    }
    button(graphics, 150, 28, 229, false, mouseX, mouseY);
    button(graphics, 165, 28, 214, false, mouseX, mouseY);
    if (previewFailed) {
      graphics.drawString(
          font,
          Component.translatable("gui.mmcece.blueprint.dynamic_invalid"),
          left + 8,
          top + 145,
          0xFFFF5555,
          true);
    }
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
      if (menu && dynamic && x >= 105 && x < 133) {
        return List.of(
            Component.translatable(
                x >= 120 ? "gui.preview.button.dynamic_pattern_plus.tip" : "gui.preview.button.dynamic_pattern_subtract.tip",
                dynamicSize < 0 ? dynamicMinimum : dynamicSize),
            Component.translatable(
                awaitingPreview ? "gui.mmcece.blueprint.dynamic_waiting" : "gui.mmcece.blueprint.dynamic_selection"));
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
      if (menu && dynamic && (x >= 105 && x < 118 || x >= 120 && x < 133)) {
        if (!awaitingPreview) {
          long current = dynamicSize < 0 ? dynamicMinimum : dynamicSize;
          int target = (int) Math.max(0L, Math.min(dynamicMaximum, current + (x >= 120 ? 1L : -1L)));
          requestPreview(target);
        }
        return true;
      }
    }
    if (x >= 150 && x < 163 && y >= 28 && y < 41 && button == 0) {
      camera.reset();
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
      camera.rotate(dx, dy);
    } else {
      camera.pan(dx, dy);
    }
    return true;
  }
  @Override
  public boolean mouseReleased(double x, double y, int button) {
    if (!dragging) {
      return super.mouseReleased(x, y, button);
    }
    dragging = false;
    if (button == 0 && dragDistance <= 4 && scene != null) {
      selected = renderer.pick(x, y);
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
        camera.zoomBy(scrollY);
      }
      return true;
    }
    return false;
  }
}
