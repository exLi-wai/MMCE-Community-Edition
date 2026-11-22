package com.lw.modularmachine.integration.mekanism.emi;

import com.lw.modularmachine.api.recipe.ChemicalRequirement;
import com.lw.modularmachine.machine.network.NetworkPortResource;
import dev.emi.emi.api.stack.EmiStack;
import java.util.Optional;
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

  public static Optional<NetworkPortResource> chemical(EmiStack stack) {
    return IMekanismAccess.INSTANCE.emiHelper().asChemicalStack(stack).filter(chemical -> !chemical.isEmpty())
        .flatMap(chemical -> chemical.getChemicalHolder().unwrapKey())
        .map(key -> new NetworkPortResource.Chemical(key.location()));
  }
}
