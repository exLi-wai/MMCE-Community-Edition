package com.lw.modularmachine.integration.mekanism.jei;

import com.lw.modularmachine.api.recipe.ChemicalRequirement;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.ChemicalStack;
import mekanism.client.recipe_viewer.jei.ChemicalStackRenderer;
import mekanism.client.recipe_viewer.jei.MekanismJEI;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class MekanismRecipeIngredients {
  private static final ChemicalStackRenderer TANK = new ChemicalStackRenderer(1, 16, 16);

  private MekanismRecipeIngredients() {
  }

  public static void addChemical(IRecipeSlotBuilder slot, ChemicalRequirement row) {
    MekanismAPI.CHEMICAL_REGISTRY.getHolder(row.chemical()).filter(holder -> !holder.is(MekanismAPI.EMPTY_CHEMICAL_KEY))
        .ifPresent(
            holder -> slot.addIngredient(MekanismJEI.TYPE_CHEMICAL, new ChemicalStack(holder, row.amount()))
                .setCustomRenderer(MekanismJEI.TYPE_CHEMICAL, TANK));
  }
}
