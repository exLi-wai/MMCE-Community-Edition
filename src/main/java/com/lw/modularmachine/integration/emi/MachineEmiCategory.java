package com.lw.modularmachine.integration.emi;

import com.lw.modularmachine.integration.jei.ClientMachineJeiCatalog;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.render.EmiRenderable;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

final class MachineEmiCategory extends EmiRecipeCategory {
  private final ClientMachineJeiCatalog.Machine machine;
  private final boolean structure;

  MachineEmiCategory(ResourceLocation id, EmiRenderable icon, ClientMachineJeiCatalog.Machine machine, boolean structure) {
    super(id, icon);
    this.machine = machine;
    this.structure = structure;
  }

  @Override
  public Component getName() {
    return structure ? Component.translatable("emi.category.mmcece.structure") : Component.translatable(
        "emi.category.mmcece.recipe",
        Component.translatable(ClientMachineJeiCatalog.translation(machine.id())));
  }
}
