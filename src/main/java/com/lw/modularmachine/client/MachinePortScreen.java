package com.lw.modularmachine.client;

import com.lw.modularmachine.menu.MachinePortMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

public final class MachinePortScreen extends AbstractContainerScreen<MachinePortMenu> {
  public MachinePortScreen(MachinePortMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = 176;
    imageHeight = 166;
    inventoryLabelY = 72;
  }

  @Override
  protected void init() {
    super.init();
    if (menu.port().canGroupInput()) {
      addRenderableWidget(InputGroupWidgets.entry(menu.containerId, leftPos + imageWidth, topPos + 4));
    }
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
    int x = leftPos;
    int y = topPos;
    String texture = menu.tierName().equals("tiny")
        || menu.tierName().equals("small")
        || menu.tierName().equals("normal")
        || menu.tierName().equals("reinforced")
        || menu.tierName().equals("big")
        || menu.tierName().equals("huge")
        || menu.tierName().equals("ludicrous") ? "inventory_" + menu.tierName() + ".png" : "inventory_normal.png";
    graphics.blit(
        ResourceLocation.fromNamespaceAndPath("mmcece", "textures/gui/" + texture),
        x,
        y,
        0,
        0,
        imageWidth,
        imageHeight,
        256,
        256);
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    super.render(graphics, mouseX, mouseY, partialTick);
    renderTooltip(graphics, mouseX, mouseY);
  }
}
