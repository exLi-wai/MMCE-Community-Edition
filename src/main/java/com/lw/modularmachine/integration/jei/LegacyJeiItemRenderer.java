package com.lw.modularmachine.integration.jei;

import java.util.List;
import mezz.jei.api.ingredients.IIngredientRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * Original count overlay: small font at 1000+, Shift reveals the exact amount.
 */
final class LegacyJeiItemRenderer implements IIngredientRenderer<ItemStack> {
  private final long amount;
  LegacyJeiItemRenderer(long amount) {
    this.amount = amount;
  }

  @Override
  public void render(GuiGraphics graphics, ItemStack stack) {
    if (stack.isEmpty()) {
      return;
    }
    var font = Minecraft.getInstance().font;
    graphics.renderItem(stack, 0, 0);
    graphics.renderItemDecorations(font, stack, 0, 0, "");
    if (amount <= 1) {
      return;
    }
    String text = Screen.hasShiftDown() ? Long.toString(amount) : abbreviated(amount);
    float scale = amount >= 1000 ? .5F : 1F;
    graphics.pose().pushPose();
    graphics.pose().translate(0, 0, 200);
    graphics.pose().scale(scale, scale, 1);
    graphics.drawString(
        font,
        text,
        (int) ((scale == .5F ? 16 : 17) / scale) - font.width(text),
        (int) ((scale == .5F ? 12 : 9) / scale),
        0xFFFFFFFF,
        true);
    graphics.pose().popPose();
  }

  private static String abbreviated(long value) {
    String[] suffixes = {"", "K", "M", "G", "T", "P", "E"};
    long number = value;
    int tier = 0;
    while (number >= 1000 && tier < suffixes.length - 1) {
      number /= 1000;
      tier++;
    }
    return tier == 0 ? Long.toString(value) : Long.toString(number) + suffixes[tier];
  }

  @Override
  public List<Component> getTooltip(ItemStack stack, Item.TooltipContext context, Player player, TooltipFlag flag) {
    return stack.getTooltipLines(context, player, flag);
  }
  @SuppressWarnings("removal")
  @Override
  public List<Component> getTooltip(ItemStack stack, TooltipFlag flag) {
    var mc = Minecraft.getInstance();
    return stack.getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, flag);
  }
}