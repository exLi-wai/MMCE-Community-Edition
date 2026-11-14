package com.lw.modularmachine.integration.jei;

import java.util.List;
import com.lw.modularmachine.client.recipe.MachineRecipeDisplay;
import mezz.jei.api.ingredients.IIngredientRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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
  private final ItemStack display;
  LegacyJeiItemRenderer(long amount) {
    this(amount, ItemStack.EMPTY);
  }

  LegacyJeiItemRenderer(long amount, ItemStack display) {
    this.amount = amount;
    this.display = display.copy();
  }

  @Override
  public void render(GuiGraphics graphics, ItemStack stack) {
    MachineRecipeDisplay.drawItem(graphics, display.isEmpty() ? stack : display, amount, 0, 0);
  }

  @Override
  public List<Component> getTooltip(ItemStack stack, Item.TooltipContext context, Player player, TooltipFlag flag) {
    return (display.isEmpty() ? stack : display).getTooltipLines(context, player, flag);
  }
  @SuppressWarnings("removal")
  @Override
  public List<Component> getTooltip(ItemStack stack, TooltipFlag flag) {
    var mc = Minecraft.getInstance();
    return (display.isEmpty() ? stack : display).getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, flag);
  }
}
