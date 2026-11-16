package com.lw.modularmachine;

import com.lw.modularmachine.client.ClientBootstrap;
import com.lw.modularmachine.client.ClientRenderRegistration;
import com.lw.modularmachine.client.ClientScreenRegistration;
import com.lw.modularmachine.client.appearance.MachineColorHandlers;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@Mod(value = ModularMachine.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = ModularMachine.MODID, value = Dist.CLIENT)
public class ModularMachineClient {
  public ModularMachineClient(ModContainer container) {
    ClientBootstrap.initialize(container);
  }

  @SubscribeEvent
  static void registerPreviewReload(RegisterClientReloadListenersEvent event) {
    ClientRenderRegistration.registerPreviewReload(event);
  }

  @SubscribeEvent
  static void onClientSetup(FMLClientSetupEvent event) {
    ClientRenderRegistration.onClientSetup(event);
  }

  @SubscribeEvent
  static void registerControllerRenderers(EntityRenderersEvent.RegisterRenderers event) {
    ClientRenderRegistration.registerControllerRenderers(event);
  }

  @SubscribeEvent
  static void wrapComponentModels(ModelEvent.ModifyBakingResult event) {
    ClientRenderRegistration.wrapComponentModels(event);
  }

  @SubscribeEvent
  static void registerBlockColors(RegisterColorHandlersEvent.Block event) {
    MachineColorHandlers.registerBlocks(event);
  }

  @SubscribeEvent
  static void registerItemColors(RegisterColorHandlersEvent.Item event) {
    MachineColorHandlers.registerItems(event);
  }

  @SubscribeEvent
  static void registerScreens(RegisterMenuScreensEvent event) {
    ClientScreenRegistration.register(event);
  }
}