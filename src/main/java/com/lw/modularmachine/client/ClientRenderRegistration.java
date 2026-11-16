package com.lw.modularmachine.client;

import com.lw.modularmachine.client.appearance.MachineVisibilityClient;
import com.lw.modularmachine.client.controller.ControllerClientPublisher;
import com.lw.modularmachine.client.controller.ControllerGeoRenderer;
import com.lw.modularmachine.client.preview.CachedPreviewRenderer;
import com.lw.modularmachine.client.preview.WorldProjection;
import com.lw.modularmachine.util.Mods;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * Renderer and model lifecycle handlers; setup work remains on the enqueued
 * client phase.
 */
@ApiStatus.Internal
public final class ClientRenderRegistration {
  private ClientRenderRegistration() {
  }

  public static void registerPreviewReload(RegisterClientReloadListenersEvent event) {
    event.registerReloadListener((ResourceManagerReloadListener) resources -> {
      CachedPreviewRenderer.invalidateAll();
      WorldProjection.invalidateMeshes();
      MachineVisibilityClient.invalidateMeshes();
      ControllerClientPublisher.invalidate();
    });
  }

  public static void onClientSetup(FMLClientSetupEvent event) {
    event.enqueueWork(MachineVisibilityClient::initializeModels);
  }

  public static void registerControllerRenderers(EntityRenderersEvent.RegisterRenderers event) {
    if (Mods.GECKOLIB.isLoaded()) {
      ControllerGeoRenderer.register(event);
    }
  }

  public static void wrapComponentModels(ModelEvent.ModifyBakingResult event) {
    MachineVisibilityClient.wrapModels(event);
  }

}
