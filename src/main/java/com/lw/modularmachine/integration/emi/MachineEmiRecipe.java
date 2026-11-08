package com.lw.modularmachine.integration.emi;

import com.lw.modularmachine.integration.jei.ClientMachineJeiCatalog;
import com.lw.modularmachine.util.minecraft.registry.RegisteredMachineItems;
import com.lw.modularmachine.registry.MachineRegistries;
import dev.emi.emi.api.recipe.BasicEmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.WidgetHolder;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

final class MachineEmiRecipe extends BasicEmiRecipe {
  private final ClientMachineJeiCatalog.Machine machine;

  MachineEmiRecipe(EmiRecipeCategory category, ClientMachineJeiCatalog.Machine machine) {
    super(category,
        ResourceLocation.fromNamespaceAndPath(machine.id().getNamespace(), "/structure/" + machine.id().getPath()), 184,
        220);
    this.machine = machine;
  }

  @Override
  public List<EmiIngredient> getCatalysts() {
    var result = new ArrayList<EmiIngredient>();
    result.add(EmiStack.of(MachineRegistries.CONTROLLER.get()));
    result.add(EmiStack.of(MachineRegistries.FACTORY_CONTROLLER.get()));
    add(result, RegisteredMachineItems.controller(machine.id()));
    add(result, RegisteredMachineItems.factory(machine.id()));
    add(result, RegisteredMachineItems.blueprint(machine.id()));
    return result;
  }

  private static void add(List<EmiIngredient> result, ItemStack stack) {
    if (!stack.isEmpty()) {
      result.add(EmiStack.of(stack));
    }
  }

  @Override
  public void addWidgets(WidgetHolder widgets) {
    widgets.add(new EmiStructurePreviewWidget(machine, 0, 0, getDisplayWidth(), getDisplayHeight()));
  }
}
