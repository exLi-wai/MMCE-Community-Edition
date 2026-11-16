package com.lw.modularmachine.registry;

import com.lw.modularmachine.command.MachineCommands;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.config.MachineStartupConfig;
import com.lw.modularmachine.integration.MachineIntegrations;
import com.lw.modularmachine.item.WrenchInteractions;
import com.lw.modularmachine.machine.appearance.MachineComponentVisibility;
import com.lw.modularmachine.machine.assembly.AssemblyInteractions;
import com.lw.modularmachine.machine.assembly.AssemblyService;
import com.lw.modularmachine.machine.controller.DedicatedControllerResources;
import com.lw.modularmachine.machine.upgrade.UpgradeCatalogService;
import com.lw.modularmachine.machine.network.NetworkPortService;
import com.lw.modularmachine.machine.recipe.MachineRecipeRegistries;
import com.lw.modularmachine.machine.recipe.MachineRecipeService;
import com.lw.modularmachine.machine.recipe.RecipeLifecyclePublisher;
import com.lw.modularmachine.machine.smart.SmartInterfaceEventPublisher;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.network.MachineJeiPayloads;
import com.lw.modularmachine.network.MachinePayloadRegistration;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.ApiStatus;

/**
 * Ordered common bootstrap; startup config and integrations precede content
 * declarations.
 */
@ApiStatus.Internal
public final class MachineBootstrap {
  private MachineBootstrap() {
  }

  public static void initialize(IEventBus modEventBus, ModContainer modContainer) {
    registerStartupConfig(modContainer);
    MachineIntegrations.initialize(modEventBus);
    registerModListeners(modEventBus);
    registerContent(modEventBus);
    modContainer.registerConfig(ModConfig.Type.COMMON, MachineConfig.SPEC, "modularmachinery/mmcece-common.toml");
    registerServerServices();
  }

  private static void registerStartupConfig(ModContainer modContainer) {
    modContainer.registerConfig(ModConfig.Type.STARTUP, MachineStartupConfig.SPEC, MachineStartupConfig.FILE_NAME);
    MachineStartupConfig.enabledAtStartup();
  }

  private static void registerModListeners(IEventBus modEventBus) {
    modEventBus.addListener(DedicatedControllerResources::register);
    MachinePayloadRegistration.registerListeners(modEventBus);
    modEventBus.addListener(MachineCapabilities::register);
  }

  private static void registerContent(IEventBus modEventBus) {
    MachineDataComponents.register(modEventBus);
    MachineRegistries.register(modEventBus);
    MachineRecipeRegistries.register(modEventBus);
  }

  private static void registerServerServices() {
    NeoForge.EVENT_BUS.register(AssemblyService.class);
    NeoForge.EVENT_BUS.register(StructureService.class);
    NeoForge.EVENT_BUS.register(MachineComponentVisibility.class);
    NeoForge.EVENT_BUS.register(MachineRecipeService.class);
    NeoForge.EVENT_BUS.register(NetworkPortService.class);
    NeoForge.EVENT_BUS.register(MachineJeiPayloads.class);
    NeoForge.EVENT_BUS.register(UpgradeCatalogService.class);
    NeoForge.EVENT_BUS.register(SmartInterfaceEventPublisher.class);
    NeoForge.EVENT_BUS.register(RecipeLifecyclePublisher.class);
    NeoForge.EVENT_BUS.register(AssemblyInteractions.class);
    NeoForge.EVENT_BUS.register(WrenchInteractions.class);
    NeoForge.EVENT_BUS.register(MachineCommands.class);
  }
}
