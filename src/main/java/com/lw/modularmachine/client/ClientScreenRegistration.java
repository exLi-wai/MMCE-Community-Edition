package com.lw.modularmachine.client;

import com.lw.modularmachine.registry.MachineRegistries;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import org.jetbrains.annotations.ApiStatus;

/** Menu screen factories in the original registration order. */
@ApiStatus.Internal
public final class ClientScreenRegistration {
  private ClientScreenRegistration() {
  }

  public static void register(RegisterMenuScreensEvent event) {
    event.register(MachineRegistries.PORT_MENU.get(), MachinePortScreen::new);
    event.register(MachineRegistries.NETWORK_PORT_MENU.get(), NetworkPortScreen::new);
    event.register(MachineRegistries.PATTERN_PROVIDER_MENU.get(), PatternProviderScreen::new);
    event.register(MachineRegistries.CONTROLLER_MENU.get(), MachineControllerScreen::new);
    event.register(MachineRegistries.RESOURCE_MENU.get(), MachineResourceScreen::new);
    event.register(MachineRegistries.FACTORY_CONTROLLER_MENU.get(), FactoryControllerScreen::new);
    event.register(MachineRegistries.UPGRADE_BUS_MENU.get(), UpgradeBusScreen::new);
    event.register(MachineRegistries.SMART_INTERFACE_MENU.get(), SmartInterfaceScreen::new);
    event.register(MachineRegistries.PARALLEL_CONTROLLER_MENU.get(), ParallelControllerScreen::new);
    event.register(MachineRegistries.GROUP_INPUT_MENU.get(), GroupInputScreen::new);
  }
}
