package com.lw.modularmachine.network;

import net.neoforged.bus.api.IEventBus;
import org.jetbrains.annotations.ApiStatus;

/** Common payload listeners, kept in their established registration order. */
@ApiStatus.Internal
public final class MachinePayloadRegistration {
  private MachinePayloadRegistration() {
  }

  public static void registerListeners(IEventBus modEventBus) {
    modEventBus.addListener(ProjectionPayloads::register);
    modEventBus.addListener(ProjectionLengthPayloads::register);
    modEventBus.addListener(MachinePayloads::register);
    modEventBus.addListener(ControllerGuiDataPayloads::register);
    modEventBus.addListener(UpgradeBusPayloads::register);
    modEventBus.addListener(SmartInterfacePayloads::register);
    modEventBus.addListener(MachineJeiPayloads::register);
    modEventBus.addListener(MachineColorPayloads::register);
    modEventBus.addListener(MachineVisibilityPayloads::register);
    modEventBus.addListener(ParallelControllerPayloads::register);
    modEventBus.addListener(GroupInputPayloads::register);
    modEventBus.addListener(NetworkPortPayloads::register);
    modEventBus.addListener(PatternProviderPayloads::register);
  }
}
