package com.lw.modularmachine.integration.emi;

import com.lw.modularmachine.machine.network.NetworkPortRecipePlan;
import com.lw.modularmachine.menu.NetworkPortMenu;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.api.recipe.handler.EmiRecipeHandler;
import dev.emi.emi.api.stack.EmiStack;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;

final class NetworkPortEmiRecipeHandler implements EmiRecipeHandler<NetworkPortMenu> {
  @Override
  public EmiPlayerInventory getInventory(AbstractContainerScreen<NetworkPortMenu> screen) {
    var items = Minecraft.getInstance().player.getInventory().items;
    return new EmiPlayerInventory(items.stream().filter(stack -> !stack.isEmpty()).map(EmiStack::of).toList());
  }

  @Override
  public boolean alwaysDisplaySupport(EmiRecipe recipe) {
    return false;
  }

  @Override
  public boolean supportsRecipe(EmiRecipe recipe) {
    var screen = EmiApi.getHandledScreen();
    return recipe instanceof MachineEmiItemRecipe
        && screen != null
        && screen.getMenu() instanceof NetworkPortMenu menu
        && menu.port().kind().isInput();
  }

  @Override
  public boolean canCraft(EmiRecipe recipe, EmiCraftContext<NetworkPortMenu> context) {
    return preview(recipe, context).error() == NetworkPortRecipePlan.Error.NONE;
  }

  @Override
  public boolean craft(EmiRecipe recipe, EmiCraftContext<NetworkPortMenu> context) {
    var plan = preview(recipe, context);
    return plan.error() == NetworkPortRecipePlan.Error.NONE
        && context.getScreenHandler().requestRecipe(recipe.getId(), 0, plan.targets());
  }

  @Override
  public List<ClientTooltipComponent> getTooltip(EmiRecipe recipe, EmiCraftContext<NetworkPortMenu> context) {
    var message = Component.translatable(preview(recipe, context).error().tooltipKey());
    return List.of(ClientTooltipComponent.create(message.getVisualOrderText()));
  }

  private static NetworkPortRecipePlan preview(EmiRecipe recipe, EmiCraftContext<NetworkPortMenu> context) {
    if (context.getType() != EmiCraftContext.Type.FILL_BUTTON || !(recipe instanceof MachineEmiItemRecipe machine)) {
      return NetworkPortRecipePlan.unavailable();
    }
    return context.getScreenHandler().previewRecipe(machine.definition());
  }
}
