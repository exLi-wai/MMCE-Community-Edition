package com.lw.modularmachine.integration.appmek;

import appeng.api.stacks.AEKey;
import com.lw.modularmachine.api.component.ChemicalStackData;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.ChemicalStack;
import org.jetbrains.annotations.ApiStatus;

/** Internal Chemical key bridge, loaded only with AE2, Mekanism and AppMek. */
@ApiStatus.Internal
public final class AppMekanisticsIntegration {
  private AppMekanisticsIntegration() {
  }

  public static AEKey key(ChemicalStackData value) {
    if (value.isEmpty()) {
      return null;
    }
    var holder = MekanismAPI.CHEMICAL_REGISTRY.getHolder(value.chemical())
        .filter(chemical -> !chemical.is(MekanismAPI.EMPTY_CHEMICAL_KEY))
        .orElseThrow(() -> new IllegalArgumentException("Unknown chemical: " + value.chemical()));

    return MekanismKey.of(new ChemicalStack(holder, 1));
  }
}
