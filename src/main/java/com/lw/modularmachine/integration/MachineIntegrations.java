package com.lw.modularmachine.integration;

import com.lw.modularmachine.integration.ae2.Ae2Integration;
import com.lw.modularmachine.integration.mekanism.MekanismIntegration;
import com.lw.modularmachine.util.Mods;
import net.neoforged.bus.api.IEventBus;
import org.jetbrains.annotations.ApiStatus;

/**
 * Optional platform adapters are installed before machine declarations are
 * initialized.
 */
@ApiStatus.Internal
public final class MachineIntegrations {
  private MachineIntegrations() {
  }

  public static void initialize(IEventBus modEventBus) {
    if (Mods.MEKANISM.isLoaded()) {
      MekanismIntegration.initialize(modEventBus);
    }
    if (Mods.AE2.isLoaded()) {
      Ae2Integration.initialize(modEventBus);
    }
  }
}
