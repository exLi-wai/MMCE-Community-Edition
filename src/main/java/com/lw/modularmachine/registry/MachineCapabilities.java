package com.lw.modularmachine.registry;

import com.lw.modularmachine.machine.PortKind;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

public final class MachineCapabilities {
  private MachineCapabilities() {
  }

  public static void register(RegisterCapabilitiesEvent event) {
    event.registerBlockEntity(
        Capabilities.ItemHandler.BLOCK,
        MachineRegistries.PORT_ENTITY.get(),
        (port, side) -> port.schemaSupported() && port.kind().resource() == PortKind.Resource.ITEM ? port
            .itemCapability() : null);

    event.registerBlockEntity(
        Capabilities.FluidHandler.BLOCK,
        MachineRegistries.PORT_ENTITY.get(),
        (port, side) -> port.schemaSupported() && port.kind().resource() == PortKind.Resource.FLUID ? port
            .fluidCapability() : null);

    event.registerBlockEntity(
        Capabilities.EnergyStorage.BLOCK,
        MachineRegistries.PORT_ENTITY.get(),
        (port, side) -> port.schemaSupported() && port.kind().resource() == PortKind.Resource.ENERGY ? port
            .energyCapability() : null);
  }
}
