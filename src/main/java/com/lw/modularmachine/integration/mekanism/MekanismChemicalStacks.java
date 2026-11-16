package com.lw.modularmachine.integration.mekanism;

import com.lw.modularmachine.api.component.ChemicalStackData;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.ChemicalStack;
import org.jetbrains.annotations.ApiStatus;

/** Exact conversion between native Chemical stacks and domain values. */
@ApiStatus.Internal
final class MekanismChemicalStacks {
  private MekanismChemicalStacks() {
  }

  static ChemicalStackData data(ChemicalStack stack) {
    if (stack.isEmpty()) {
      return ChemicalStackData.EMPTY;
    }
    var key = stack.getChemicalHolder().getKey();

    return new ChemicalStackData(
        key == null ? MekanismAPI.CHEMICAL_REGISTRY.getDefaultKey() : key.location(),
        stack.getAmount());
  }

  static ChemicalStack stack(ChemicalStackData value) {
    if (value.isEmpty()) {
      return ChemicalStack.EMPTY;
    }
    var chemical = MekanismAPI.CHEMICAL_REGISTRY.getHolder(value.chemical())
        .filter(holder -> !holder.is(MekanismAPI.EMPTY_CHEMICAL_KEY))
        .orElseThrow(() -> new IllegalArgumentException("Unknown chemical: " + value.chemical()));

    return new ChemicalStack(chemical, value.amount());
  }
}
