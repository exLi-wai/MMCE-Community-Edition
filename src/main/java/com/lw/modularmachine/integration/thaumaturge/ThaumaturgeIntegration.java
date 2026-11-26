package com.lw.modularmachine.integration.thaumaturge;

import com.leclowndu93150.thaumaturge.api.aspect.AspectCapabilities;
import com.leclowndu93150.thaumaturge.api.essentia.EssentiaCapabilities;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.util.Mods;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class ThaumaturgeIntegration {
  private ThaumaturgeIntegration() {
  }

  public static void initialize(IEventBus bus) {
    bus.addListener(ThaumaturgeIntegration::register);
    if (Mods.AE2.isLoaded() && Mods.APPLIED_THAUMATURGE.isLoaded()) {
      bus.addListener(MeEssentiaCapabilities::register);
    }
  }

  private static void register(RegisterCapabilitiesEvent event) {
    EssentiaCapabilities.registerConduit(event, MachineRegistries.PIPE_ESSENTIA_ENTITY.get(), (hatch, side) -> hatch);
    event.registerBlockEntity(
        AspectCapabilities.CONTAINER,
        MachineRegistries.PIPE_ESSENTIA_ENTITY.get(),
        (hatch, side) -> hatch);
  }
}
