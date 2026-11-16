package com.lw.modularmachine.integration.mekanism;

import com.lw.modularmachine.api.component.ResourceType;
import com.lw.modularmachine.machine.chemical.ChemicalPlatform;
import com.lw.modularmachine.registry.MachineRegistries;
import mekanism.common.capabilities.Capabilities;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * Mekanism module registration, entered only after the central Mods gate
 * succeeds.
 */
@ApiStatus.Internal
public final class MekanismIntegration {
  private MekanismIntegration() {
  }

  public static void initialize(IEventBus bus) {
    ChemicalPlatform.install(new MekanismChemicalAdapter());
    bus.addListener(MekanismIntegration::registerCapabilities);
  }

  private static void registerCapabilities(RegisterCapabilitiesEvent event) {
    event.registerBlockEntity(
        Capabilities.CHEMICAL.block(),
        MachineRegistries.PORT_ENTITY.get(),
        (port, side) -> port.schemaSupported()
            && port.kind().resource() == ResourceType.CHEMICAL ? new MekanismPortHandler(port) : null);
  }
}
