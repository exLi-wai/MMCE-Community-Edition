package com.lw.modularmachine.integration.jei;

import java.util.List;
import java.util.function.UnaryOperator;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.ingredients.IIngredientRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import org.jetbrains.annotations.ApiStatus;

/**
 * Keeps JEI's ingredient tooltips for slots painted by the shared recipe
 * display.
 */
@ApiStatus.Internal
public final class RecipeSlotTooltipRenderer<T> implements IIngredientRenderer<T> {
  private final IIngredientRenderer<T> delegate;
  private final int size;
  private final UnaryOperator<T> tooltipIngredient;

  public RecipeSlotTooltipRenderer(IIngredientRenderer<T> delegate, int size) {
    this(delegate, size, UnaryOperator.identity());
  }

  RecipeSlotTooltipRenderer(IIngredientRenderer<T> delegate, int size, UnaryOperator<T> tooltipIngredient) {
    this.delegate = delegate;
    this.size = size;
    this.tooltipIngredient = tooltipIngredient;
  }

  @Override
  public void render(GuiGraphics graphics, T ingredient) {
  }

  @Override
  public void render(GuiGraphics graphics, T ingredient, int x, int y) {
  }

  @Override
  public int getWidth() {
    return size;
  }

  @Override
  public int getHeight() {
    return size;
  }

  @Override
  public Font getFontRenderer(Minecraft minecraft, T ingredient) {
    return delegate.getFontRenderer(minecraft, tooltipIngredient.apply(ingredient));
  }

  @Override
  @SuppressWarnings("removal")
  public List<Component> getTooltip(T ingredient, TooltipFlag flag) {
    return delegate.getTooltip(tooltipIngredient.apply(ingredient), flag);
  }

  @Override
  public List<Component> getTooltip(T ingredient, Item.TooltipContext context, Player player, TooltipFlag flag) {
    return delegate.getTooltip(tooltipIngredient.apply(ingredient), context, player, flag);
  }

  @Override
  public void getTooltip(ITooltipBuilder tooltip, T ingredient, Item.TooltipContext context, Player player, TooltipFlag flag) {
    delegate.getTooltip(tooltip, tooltipIngredient.apply(ingredient), context, player, flag);
  }
}
