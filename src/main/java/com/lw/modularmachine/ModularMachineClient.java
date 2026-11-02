package com.lw.modularmachine;

import com.lw.modularmachine.client.*;
import com.lw.modularmachine.client.preview.BlueprintPreviewScreen;
import com.lw.modularmachine.client.preview.WorldProjection;
import com.lw.modularmachine.config.MachineClientConfig;
import com.lw.modularmachine.integration.jei.ClientMachineJeiCatalog;
import com.lw.modularmachine.machine.blueprint.BlueprintPreviewActions;
import com.lw.modularmachine.menu.GroupInputMenu;
import com.lw.modularmachine.menu.ParallelControllerMenu;
import com.lw.modularmachine.menu.SmartInterfaceMenu;
import com.lw.modularmachine.network.*;
import com.lw.modularmachine.registry.MachineRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = ModularMachine.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = ModularMachine.MODID, value = Dist.CLIENT)
public class ModularMachineClient {
  public ModularMachineClient(ModContainer container) {
    container.registerConfig(ModConfig.Type.CLIENT, MachineClientConfig.SPEC);
    container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    MachineJeiPayloads.setReceiver(ClientMachineJeiCatalog::receive);
    ProjectionPayloads.setReceiver(WorldProjection::receive);
    MachinePayloads.setClientReceiver(MachineControllerStatusClient::receive);
    MachinePayloads.setResourceClientReceiver(MachineControllerStatusClient::receiveResource);
    MachinePayloads.setFactoryClientReceiver(MachineControllerStatusClient::receiveFactory);
    UpgradeBusPayloads.setClientReceiver(MachineControllerStatusClient::receiveUpgradeBus);
    ModularMachineClient.SetClientReceiver();
    ModularMachineClient.SetClientOpener();
  }

  @SubscribeEvent
  static void registerPreviewReload(RegisterClientReloadListenersEvent event) {
    event.registerReloadListener((ResourceManagerReloadListener) resources -> {
      WorldProjection.invalidateMeshes();
      if (Minecraft.getInstance().screen instanceof BlueprintPreviewScreen screen) {
        screen.invalidateMeshes();
      }
    });
  }
  @SubscribeEvent
  static void onClientSetup(FMLClientSetupEvent event) {
  }

  @SubscribeEvent
  static void registerScreens(RegisterMenuScreensEvent event) {
    event.register(MachineRegistries.PORT_MENU.get(), MachinePortScreen::new);
    event.register(MachineRegistries.CONTROLLER_MENU.get(), MachineControllerScreen::new);
    event.register(MachineRegistries.RESOURCE_MENU.get(), MachineResourceScreen::new);
    event.register(MachineRegistries.FACTORY_CONTROLLER_MENU.get(), FactoryControllerScreen::new);
    event.register(MachineRegistries.UPGRADE_BUS_MENU.get(), UpgradeBusScreen::new);
    event.register(MachineRegistries.SMART_INTERFACE_MENU.get(), SmartInterfaceScreen::new);
    event.register(MachineRegistries.PARALLEL_CONTROLLER_MENU.get(), ParallelControllerScreen::new);
    event.register(MachineRegistries.GROUP_INPUT_MENU.get(), GroupInputScreen::new);
  }

  static void SetClientReceiver() {
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
  }

  static void SetClientOpener() {
    BlueprintPreviewActions.setClientOpener(
        (machine, hand) -> Minecraft.getInstance().setScreen(new BlueprintPreviewScreen(machine, hand)));
  }
}
