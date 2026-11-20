package com.lw.modularmachine.integration.mekanism.emi;

import com.lw.modularmachine.api.recipe.ChemicalRequirement;
import dev.emi.emi.api.stack.EmiStack;
import mekanism.api.IMekanismAccess;
import mekanism.api.MekanismAPI;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class MekanismEmiIngredients {
  private MekanismEmiIngredients() {
  }

  public static EmiStack stack(ChemicalRequirement row) {
    return MekanismAPI.CHEMICAL_REGISTRY.getHolder(row.chemical())
        .filter(holder -> !holder.is(MekanismAPI.EMPTY_CHEMICAL_KEY))
        .map(holder -> IMekanismAccess.INSTANCE.emiHelper().createEmiStack(holder, row.amount()))
        .orElse(EmiStack.EMPTY);
  }
}
