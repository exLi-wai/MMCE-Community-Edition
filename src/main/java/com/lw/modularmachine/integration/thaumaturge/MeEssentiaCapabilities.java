package com.lw.modularmachine.integration.thaumaturge;

import appeng.api.AECapabilities;
import com.lw.modularmachine.registry.MachineRegistries;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

final class MeEssentiaCapabilities {
  private MeEssentiaCapabilities() {
  }

  static void register(RegisterCapabilitiesEvent event) {
    event.registerBlockEntity(
        AECapabilities.IN_WORLD_GRID_NODE_HOST,
        MachineRegistries.ME_ESSENTIA_ENTITY.get(),
        (hatch, side) -> hatch);
  }
}
