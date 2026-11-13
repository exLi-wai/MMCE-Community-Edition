package com.lw.modularmachine.integration.ae2;

import appeng.api.AECapabilities;
import appeng.api.stacks.AEKey;
import com.lw.modularmachine.api.component.ChemicalStackData;
import com.lw.modularmachine.blockentity.NetworkPortBlockEntity;
import com.lw.modularmachine.integration.appmek.AppMekanisticsIntegration;
import com.lw.modularmachine.machine.network.MachineNetworkPlatform;
import com.lw.modularmachine.machine.network.NetworkPortResource;
import com.lw.modularmachine.machine.network.pattern.PatternNetworkPlatform;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.util.Mods;
import java.util.function.Function;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.jetbrains.annotations.ApiStatus;

/** Internal AE adapter installed only after the central Mods gate succeeds. */
@ApiStatus.Internal
public final class Ae2Integration implements MachineNetworkPlatform.Adapter {
  private final Function<ChemicalStackData, AEKey> chemicalKeys;

  private Ae2Integration(Function<ChemicalStackData, AEKey> chemicalKeys) {
    this.chemicalKeys = chemicalKeys;
  }

  public static void initialize(IEventBus bus) {
    Function<ChemicalStackData, AEKey> chemicals = null;
    Function<AEKey, NetworkPortResource> chemicalDecoder = null;
    if (Mods.MEKANISM.isLoaded() && Mods.APPMEK.isLoaded()) {
      chemicals = AppMekanisticsIntegration::key;
      chemicalDecoder = AppMekanisticsIntegration::resource;
    }
    MachineNetworkPlatform.install(new Ae2Integration(chemicals));
    var patternChemicals = chemicals;
    var patternDecoder = chemicalDecoder;
    PatternNetworkPlatform.install(provider -> new Ae2PatternConnection(provider, patternChemicals, patternDecoder));
    Ae2PatternJobs.initialize();
    bus.addListener(Ae2Integration::registerCapabilities);
  }

  private static void registerCapabilities(RegisterCapabilitiesEvent event) {
    event.registerBlockEntity(
        AECapabilities.IN_WORLD_GRID_NODE_HOST,
        MachineRegistries.NETWORK_PORT_ENTITY.get(),
        (port, context) -> port.connection() instanceof Ae2Connection connection ? connection : null);
    event.registerBlockEntity(
        AECapabilities.IN_WORLD_GRID_NODE_HOST,
        MachineRegistries.PATTERN_PROVIDER_ENTITY.get(),
        (provider, context) -> provider.connection() instanceof Ae2PatternConnection connection ? connection : null);
  }

  @Override
  public MachineNetworkPlatform.Connection create(NetworkPortBlockEntity port) {
    return new Ae2Connection(port, chemicalKeys);
  }

  @Override
  public boolean chemicalAvailable() {
    return chemicalKeys != null;
  }
}
