package com.lw.modularmachine.client.network;

import com.lw.modularmachine.client.appearance.MachineColorHandlers;
import com.lw.modularmachine.client.appearance.MachineVisibilityClient;
import com.lw.modularmachine.client.controller.ControllerClientPublisher;
import com.lw.modularmachine.client.MachineControllerStatusClient;
import com.lw.modularmachine.client.NetworkPortScreen;
import com.lw.modularmachine.client.PatternProviderScreen;
import com.lw.modularmachine.client.preview.WorldProjection;
import com.lw.modularmachine.client.preview.ClientMachineCatalog;
import com.lw.modularmachine.integration.kubejs.KubeJSControllerClientBridge;
import com.lw.modularmachine.menu.FactoryControllerMenu;
import com.lw.modularmachine.menu.GroupInputMenu;
import com.lw.modularmachine.menu.MachineControllerMenu;
import com.lw.modularmachine.menu.ParallelControllerMenu;
import com.lw.modularmachine.menu.SmartInterfaceMenu;
import com.lw.modularmachine.network.ControllerGuiDataPayloads;
import com.lw.modularmachine.network.GroupInputPayloads;
import com.lw.modularmachine.network.MachineColorPayloads;
import com.lw.modularmachine.network.MachineJeiPayloads;
import com.lw.modularmachine.network.MachinePayloads;
import com.lw.modularmachine.network.MachineVisibilityPayloads;
import com.lw.modularmachine.network.NetworkPortPayloads;
import com.lw.modularmachine.network.ParallelControllerPayloads;
import com.lw.modularmachine.network.PatternProviderPayloads;
import com.lw.modularmachine.network.ProjectionPayloads;
import com.lw.modularmachine.network.SmartInterfacePayloads;
import com.lw.modularmachine.network.UpgradeBusPayloads;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.ApiStatus;

/**
 * Client packet delivery bindings; each callback retains its original menu or
 * screen checks.
 */
@ApiStatus.Internal
public final class ClientPayloadReceivers {
  private ClientPayloadReceivers() {
  }

  public static void registerStatusReceivers() {
    MachinePayloads.setResourceClientReceiver(MachineControllerStatusClient::receiveResource);
    MachinePayloads.setFactoryClientReceiver(MachineControllerStatusClient::receiveFactory);
    registerComponentReceivers();
  }

  private static void registerComponentReceivers() {
    MachineColorPayloads.setClientReceiver(MachineColorHandlers::receive);
    MachineVisibilityPayloads.setClientReceiver(MachineVisibilityClient::receive);
    MachinePayloads.setClientReceiver(MachineControllerStatusClient::receive);
    NetworkPortPayloads.setClientReceiver(NetworkPortScreen::receive);
    PatternProviderPayloads.setClientReceiver(PatternProviderScreen::receive);
    KubeJSControllerClientBridge.setInstaller(ControllerClientPublisher::install);
    UpgradeBusPayloads.setClientReceiver(MachineControllerStatusClient::receiveUpgradeBus);
    ParallelControllerPayloads.setClientReceiver(payload -> {
      var player = Minecraft.getInstance().player;
      if (player != null && player.containerMenu instanceof ParallelControllerMenu menu) {
        menu.acceptStatus(payload);
      }
    });
    GroupInputPayloads.setClientReceiver(payload -> {
      var player = Minecraft.getInstance().player;
      if (player != null && player.containerMenu instanceof GroupInputMenu menu) {
        menu.acceptStatus(payload);
      }
    });
    SmartInterfacePayloads.setClientReceiver(payload -> {
      var player = Minecraft.getInstance().player;
      if (player != null && player.containerMenu instanceof SmartInterfaceMenu menu) {
        menu.acceptStatus(payload);
      }
    });
    ControllerGuiDataPayloads.setClientReceiver(update -> {
      var player = Minecraft.getInstance().player;
      if (player == null) {
        return;
      }
      if (player.containerMenu instanceof MachineControllerMenu menu) {
        menu.acceptGuiData(update);
      } else if (player.containerMenu instanceof FactoryControllerMenu menu) {
        menu.acceptGuiData(update);
      }
    });
  }

  public static void registerCatalogReceivers() {
    MachineJeiPayloads.setReceiver(ClientMachineCatalog::receive);
    ProjectionPayloads.setReceiver(WorldProjection::receive);
  }
}
