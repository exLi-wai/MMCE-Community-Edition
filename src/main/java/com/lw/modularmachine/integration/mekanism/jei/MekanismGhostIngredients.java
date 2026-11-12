package com.lw.modularmachine.integration.mekanism.jei;

import com.lw.modularmachine.machine.network.NetworkPortResource;
import java.util.Optional;
import mekanism.api.chemical.ChemicalStack;
import mekanism.client.recipe_viewer.jei.MekanismJEI;
import mezz.jei.api.ingredients.IIngredientType;
import org.jetbrains.annotations.ApiStatus;

/**
 * Loaded only when Mekanism is present; optional chemical types stay in this
 * adapter.
 */
@ApiStatus.Internal
public final class MekanismGhostIngredients {
  private MekanismGhostIngredients() {
  }

  public static <I> Optional<NetworkPortResource> convert(IIngredientType<I> type, I ingredient) {
    if (!type.equals(MekanismJEI.TYPE_CHEMICAL) || !(ingredient instanceof ChemicalStack stack) || stack.isEmpty()) {
      return Optional.empty();
    }
    var key = stack.getChemicalHolder().getKey();

    return key == null ? Optional.empty() : Optional.of(new NetworkPortResource.Chemical(key.location()));
  }
}
