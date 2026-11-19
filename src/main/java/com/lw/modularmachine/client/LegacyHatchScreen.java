package com.lw.modularmachine.client;

import com.lw.modularmachine.menu.LegacyHatchMenu;
import com.lw.modularmachine.util.Mods;
import java.text.NumberFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import com.lw.modularmachine.integration.pneumaticcraft.CompressedAirGauge;

final class LegacyHatchScreen extends AbstractContainerScreen<LegacyHatchMenu> {
  private static final ResourceLocation TINY = ResourceLocation
      .fromNamespaceAndPath("mmcece", "textures/gui/inventory_tiny.png");
  private static final ResourceLocation STARLIGHT = ResourceLocation
      .fromNamespaceAndPath("mmcece", "textures/gui/gui_starlightinputhatch.png");

  LegacyHatchScreen(LegacyHatchMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = 176;
    imageHeight = 166;
    inventoryLabelY = 73;
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
    graphics.blit(
        menu.kind() == LegacyHatchMenu.Kind.STARLIGHT ? STARLIGHT : TINY,
        leftPos,
        topPos,
        0,
        0,
        imageWidth,
        imageHeight);
    switch (menu.kind()) {
      case LIFE_ESSENCE -> {
      }
      case COMPRESSED_AIR -> renderAir(graphics);
      case STARLIGHT -> renderStarlight(graphics);
    }
  }

  private void renderAir(GuiGraphics graphics) {
    graphics.fill(leftPos + 7, topPos + 18, leftPos + 169, topPos + 73, 0xFFC6C6C6);
    for (int index = 0; index < 4; index++) {
      int x = leftPos + menu.kind().slotX(index) - 1;
      int y = topPos + menu.kind().slotY() - 1;
      graphics.fill(x, y, x + 18, y + 18, 0xFFFFFFFF);
      graphics.fill(x, y, x + 17, y + 17, 0xFF8B8B8B);
      graphics.fill(x, y, x + 17, y + 1, 0xFF373737);
      graphics.fill(x, y, x + 1, y + 17, 0xFF373737);
    }
    if (Mods.PNEUMATIC_CRAFT.isLoaded() && menu.value(1) > 0) {
      CompressedAirGauge.render(
          graphics,
          font,
          menu.value(0) / (float) menu.value(1),
          Float.intBitsToFloat(menu.value(2)),
          Float.intBitsToFloat(menu.value(3)),
          leftPos + 27,
          topPos + 49);
    }
  }

  private void renderStarlight(GuiGraphics graphics) {
    int capacity = menu.value(1);
    int width = capacity > 0 ? (int) (165L * Math.min(menu.value(0), capacity) / capacity) : 0;
    graphics.fill(leftPos + 6, topPos + 69, leftPos + 6 + width, topPos + 79, 0xFF8CA6EB);
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    String label = font.plainSubstrByWidth(title.getString(), 164);
    graphics.drawString(font, label, (imageWidth - font.width(label)) / 2, 6, 0x404040, false);
    if (menu.kind() == LegacyHatchMenu.Kind.LIFE_ESSENCE) {
      var status = Component.translatable("message.mmcece.life_essence.stock", menu.value(0), menu.value(1));
      graphics.drawString(font, status, (imageWidth - font.width(status)) / 2, 53, 0x404040, false);
      graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0x404040, false);
    } else if (menu.kind() == LegacyHatchMenu.Kind.COMPRESSED_AIR) {
      graphics.drawString(font, Component.translatable("menu.mmcece.compressed_air.upgrades"), 53, 19, 0x404040, false);
    } else {
      var format = NumberFormat.getNumberInstance();
      graphics.drawString(
          font,
          Component.translatable("menu.mmcece.starlight.sky", format.format(menu.value(2) / 1_000D)),
          8,
          24,
          0x404040,
          false);
      graphics.drawString(
          font,
          Component.translatable("menu.mmcece.starlight.unattuned", format.format(menu.value(3) / 1_000D)),
          8,
          36,
          0x404040,
          false);
      graphics.drawString(
          font,
          Component.translatable(
              "menu.mmcece.starlight.attuned",
              format.format(Math.max(0, menu.value(0) - menu.value(2) - menu.value(3)) / 1_000D)),
          8,
          48,
          0x404040,
          false);
    }
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    super.render(graphics, mouseX, mouseY, partialTick);
    renderTooltip(graphics, mouseX, mouseY);
    if (menu.kind() == LegacyHatchMenu.Kind.STARLIGHT && isHovering(6, 69, 165, 10, mouseX, mouseY)) {
      var format = NumberFormat.getNumberInstance();
      graphics.renderTooltip(
          font,
          Component.translatable(
              "menu.mmcece.starlight.stock",
              format.format(menu.value(0) / 1_000D),
              format.format(menu.value(1) / 1_000D)),
          mouseX,
          mouseY);
    }
  }
}
