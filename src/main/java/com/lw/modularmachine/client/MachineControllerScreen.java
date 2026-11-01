package com.lw.modularmachine.client;

import com.lw.modularmachine.menu.MachineControllerMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

public final class MachineControllerScreen extends AbstractContainerScreen<MachineControllerMenu> {
  public MachineControllerScreen(MachineControllerMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    MachineControllerStatusClient.attach(this);
    imageWidth = 176;
    imageHeight = 213;
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    super.render(graphics, mouseX, mouseY, partialTick);
    renderTooltip(graphics, mouseX, mouseY);
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
    int x = leftPos;
    int y = topPos;
    graphics.blit(
        ResourceLocation.fromNamespaceAndPath("mmcece", "textures/gui/guicontroller_large.png"),
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
    graphics.pose().pushPose();
    graphics.pose().scale(.72F, .72F, 1F);
    graphics.drawString(font, Component.translatable("menu.mmcece.controller.read_only"), 12, 12, 0xFFFFFF, true);
    var status = menu.status();
    if (status == null) {
      graphics
          .drawString(font, Component.translatable("menu.mmcece.controller.status_waiting"), 12, 24, 0xFFFFFF, true);
      graphics.pose().popPose();
      return;
    }
    graphics.drawString(
        font,
        Component.translatable("menu.mmcece.controller.structure", status.structureState()),
        12,
        24,
        0xFFFFFF,
        true);
    graphics.drawString(
        font,
        Component.translatable("menu.mmcece.controller.recipe", status.recipeStatus()),
        12,
        34,
        0xFFFFFF,
        true);
    graphics.drawString(
        font,
        Component.translatable("menu.mmcece.controller.progress", status.progress(), status.duration()),
        12,
        44,
        0xFFFFFF,
        true);
    graphics.drawString(
        font,
        Component.translatable("menu.mmcece.controller.orientation", status.front(), status.roll()),
        12,
        54,
        0xFFFFFF,
        true);
    graphics.drawString(
        font,
        Component.translatable("menu.mmcece.controller.epoch", status.definitionEpoch()),
        12,
        64,
        0xFFFFFF,
        true);
    graphics.pose().popPose();
  }
}
