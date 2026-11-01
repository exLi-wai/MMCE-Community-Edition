package com.lw.modularmachine.client;

import com.lw.modularmachine.menu.UpgradeBusMenu;
import com.lw.modularmachine.network.MachinePayloads;
import com.lw.modularmachine.network.UpgradeBusPayloads;
import net.minecraft.client.Minecraft;

public final class MachineControllerStatusClient {
  private static MachinePayloads.ControllerStatusPayload pendingController;
  private static MachinePayloads.ResourcePortStatusPayload pendingResource;
  private static MachinePayloads.FactoryStatusPayload pendingFactory;

  private MachineControllerStatusClient() {
  }

  public static void receive(MachinePayloads.ControllerStatusPayload payload) {
    pendingController = payload;
    var screen = Minecraft.getInstance().screen;
    if (screen instanceof MachineControllerScreen controllerScreen) {
      controllerScreen.getMenu().acceptStatus(payload);
      pendingController = null;
    }
  }

  public static void receiveResource(MachinePayloads.ResourcePortStatusPayload payload) {
    pendingResource = payload;
    var screen = Minecraft.getInstance().screen;
    if (screen instanceof MachineResourceScreen resourceScreen) {
      resourceScreen.getMenu().acceptStatus(payload);
      pendingResource = null;
    }
  }

  public static void receiveFactory(MachinePayloads.FactoryStatusPayload payload) {
    pendingFactory = payload;
    var screen = Minecraft.getInstance().screen;
    if (screen instanceof FactoryControllerScreen factoryScreen) {
      factoryScreen.getMenu().acceptStatus(payload);
      pendingFactory = null;
    }
  }

  public static void receiveUpgradeBus(UpgradeBusPayloads.Status payload) {
    var player = Minecraft.getInstance().player;
    if (player != null && player.containerMenu instanceof UpgradeBusMenu menu) {
      menu.acceptStatus(payload);
    }
  }

  public static void attach(MachineControllerScreen screen) {
    if (pendingController != null && pendingController.position().equals(screen.getMenu().controller().getBlockPos())) {
      screen.getMenu().acceptStatus(pendingController);
      pendingController = null;
    }
  }

  public static void attach(MachineResourceScreen screen) {
    if (pendingResource != null && pendingResource.position().equals(screen.getMenu().port().getBlockPos())) {
      screen.getMenu().acceptStatus(pendingResource);
      pendingResource = null;
    }
  }

  public static void attach(FactoryControllerScreen screen) {
    if (pendingFactory != null && pendingFactory.position().equals(screen.getMenu().factory().getBlockPos())) {
      screen.getMenu().acceptStatus(pendingFactory);
      pendingFactory = null;
    }
  }
}
