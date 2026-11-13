package com.lw.modularmachine.client;

import com.lw.modularmachine.blockentity.SmartInterfaceBlockEntity;
import com.lw.modularmachine.menu.SmartInterfaceMenu;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import org.lwjgl.glfw.GLFW;

/**
 * Legacy smart-interface geometry. Labels and editable values are
 * server-authored snapshots.
 */
public final class SmartInterfaceScreen extends AbstractContainerScreen<SmartInterfaceMenu> {
  private static final ResourceLocation TEXTURE = ResourceLocation
      .fromNamespaceAndPath("mmcece", "textures/gui/guismartinterface.png");
  private EditBox input;
  private Button previous;
  private Button next;
  private BlockPos selectedOwner;
  private String selectedType;

  public SmartInterfaceScreen(SmartInterfaceMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = 176;
    imageHeight = 166;
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    super.render(graphics, mouseX, mouseY, partialTick);
    renderTooltip(graphics, mouseX, mouseY);
  }

  @Override
  protected void init() {
    super.init();
    input = addRenderableWidget(
        new EditBox(font, leftPos + 98, topPos + 35, 70, 10, Component.translatable("menu.mmcece.smart.value_input")));
    input.setMaxLength(32);
    input.setFilter(text -> text.matches("[-+0-9.eE]*"));
    previous = addRenderableWidget(
        Button.builder(Component.translatable("menu.mmcece.smart.previous"), button -> select(index() - 1))
            .bounds(leftPos + 7, topPos + 58, 40, 20).build());
    next = addRenderableWidget(
        Button.builder(Component.translatable("menu.mmcece.smart.next"), button -> select(index() + 1))
            .bounds(leftPos + 129, topPos + 58, 40, 20).build());
  }

  private List<SmartInterfaceBlockEntity.Entry> entries() {
    return menu.status() == null ? List.of() : menu.status().entries();
  }

  private int index() {
    var entries = entries();
    for (int index = 0; index < entries.size(); index++) {
      var binding = entries.get(index).binding();
      if (binding.controller().equals(selectedOwner) && binding.type().equals(selectedType)) {
        return index;
      }
    }
    if (!entries.isEmpty()) {
      select(0);
    }

    return 0;
  }

  private void select(int index) {
    var entries = entries();
    if (!entries.isEmpty()) {
      var entry = entries.get(Math.clamp(index, 0, entries.size() - 1));
      selectedOwner = entry.binding().controller();
      selectedType = entry.binding().type();
      if (input != null) {
        input.setValue("");
      }
    }
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
    graphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, 256, 256);
    var entries = entries();
    int index = index();
    input.visible = !entries.isEmpty();
    previous.visible = input.visible;
    next.visible = input.visible;
    previous.active = index > 0;
    next.active = index + 1 < entries.size();
    input.setEditable(!entries.isEmpty() && entries.get(index).editable());
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    var entries = entries();
    int index = index();
    graphics.drawString(
        font,
        Component.translatable("menu.mmcece.smart.title", entries.size(), entries.isEmpty() ? 0 : index + 1),
        4,
        4,
        0xFFFFFF,
        true);
    if (entries.isEmpty()) {
      graphics.drawString(font, Component.translatable("menu.mmcece.smart.unbound"), 7, 16, 0xFFFFFF, true);
      return;
    }
    var entry = entries.get(index);
    String machine = Component.translatable(entry.machineName()).getString() + " (" + entry.binding().controller()
        .toShortString() + ")";
    graphics.drawString(font, font.plainSubstrByWidth(machine, 162), 7, 16, 0xFFFFFF, true);
    graphics.drawString(font, Component.translatable(entry.specification().header()), 7, 26, 0xFFFFFF, true);
    String label = entry.specification().valueLabel().isEmpty() ? "menu.mmcece.smart.value" : entry.specification()
        .valueLabel();
    graphics.drawString(font, Component.translatable(label, entry.binding().value()), 7, 36, 0xFFFFFF, true);
    graphics.drawString(
        font,
        Component.translatable(entry.editable() ? entry.specification().footer() : "menu.mmcece.smart.inactive"),
        7,
        46,
        0xFFFFFF,
        true);
  }

  @Override
  public boolean keyPressed(int key, int scanCode, int modifiers) {
    if (input.isFocused() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
      var entries = entries();
      if (!entries.isEmpty()) {
        var entry = entries.get(index());
        try {
          double value = Double.parseDouble(input.getValue());
          if (entry.editable() && entry.specification().accepts(value)) {
            menu.requestValue(entry, value);
            input.setValue("");
          }
        } catch (NumberFormatException invalid) {
          return true;
        }
      }
      return true;
    }

    return super.keyPressed(key, scanCode, modifiers);
  }
}
