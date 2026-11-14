package com.lw.modularmachine.client;

import com.lw.modularmachine.menu.MachineControllerMenu;
import com.lw.modularmachine.client.controller.ControllerClientPublisher;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * Original-style controller panel: found blueprint, found structure, status and
 * recipe progress as a percentage.
 */
public final class MachineControllerScreen extends AbstractContainerScreen<MachineControllerMenu> {
  private static final int TEXT_WIDTH = 187;

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

    int x = 12;
    int y = 12;
    var status = menu.status();
    boolean formed = status != null && "CONFIRMED".equals(status.structureState());
    Component blueprint = ControllerStatusLabels.blueprint(menu);
    // Original layout: once the machine is formed without a blueprint item, the
    // "found blueprint" line disappears instead of saying "none".
    if (blueprint != null || !formed) {
      y = draw(
          graphics,
          Component.translatable(
              "menu.mmcece.controller.blueprint",
              blueprint == null ? ControllerStatusLabels.none() : blueprint),
          x,
          y) + 5;
    }

    if (status == null) {
      draw(graphics, Component.translatable("menu.mmcece.controller.status_waiting"), x, y);
      graphics.pose().popPose();
      return;
    }

    Component structure = ControllerStatusLabels.structure(status.structureState(), status.machineId());
    y = draw(
        graphics,
        Component.translatable(
            "menu.mmcece.controller.structure",
            structure == null ? ControllerStatusLabels.none() : structure),
        x,
        y) + 5;
    y = draw(
        graphics,
        Component.translatable("menu.mmcece.controller.status", ControllerStatusLabels.state(status)),
        x,
        y) + 5;
    if (status.duration() > 0) {
      y = draw(
          graphics,
          Component.translatable(
              "menu.mmcece.controller.progress",
              ControllerStatusLabels.percent(status.progress(), status.duration()) + "%"),
          x,
          y) + 5;
    }
    ControllerStatusLabels
        .drawPerformance(graphics, font, x, y, status.averageTickMicros(), status.averageSearchMicros());
    ControllerClientPublisher
        .drawGui(menu.controller(), menu.guiCustomData(), graphics, font, x, y + 25, TEXT_WIDTH, (int) (146 / .72F));
    graphics.pose().popPose();
  }

  private int draw(GuiGraphics graphics, Component text, int x, int y) {
    for (var line : font.split(text, TEXT_WIDTH)) {
      graphics.drawString(font, line, x, y, 0xFFFFFF, true);
      y += 10;
    }

    return y;
  }
}
