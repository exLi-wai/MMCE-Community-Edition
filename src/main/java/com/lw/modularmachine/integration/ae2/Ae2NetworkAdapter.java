package com.lw.modularmachine.integration.ae2;

import appeng.api.stacks.AEKey;
import com.lw.modularmachine.api.component.ChemicalStackData;
import com.lw.modularmachine.blockentity.NetworkPortBlockEntity;
import com.lw.modularmachine.machine.network.MachineNetworkPlatform;
import java.util.function.Function;
import org.jetbrains.annotations.ApiStatus;

/** Runtime network adapter; owns only the optional Chemical key conversion. */
@ApiStatus.Internal
final class Ae2NetworkAdapter implements MachineNetworkPlatform.Adapter {
  private final Function<ChemicalStackData, AEKey> chemicalKeys;

  Ae2NetworkAdapter(Function<ChemicalStackData, AEKey> chemicalKeys) {
    this.chemicalKeys = chemicalKeys;
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
