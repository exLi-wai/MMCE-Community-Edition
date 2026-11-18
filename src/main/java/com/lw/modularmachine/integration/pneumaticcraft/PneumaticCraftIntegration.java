package com.lw.modularmachine.integration.pneumaticcraft;

import com.lw.modularmachine.registry.MachineRegistries;
import me.desht.pneumaticcraft.api.PNCCapabilities;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class PneumaticCraftIntegration {
  private PneumaticCraftIntegration() {
  }

  public static void initialize(IEventBus bus) {
    bus.addListener(PneumaticCraftIntegration::registerCapabilities);
  }

  private static void registerCapabilities(RegisterCapabilitiesEvent event) {
    event.registerBlockEntity(
        PNCCapabilities.AIR_HANDLER_MACHINE,
        MachineRegistries.COMPRESSED_AIR_ENTITY.get(),
        (hatch, side) -> hatch.handler());
  }
}
