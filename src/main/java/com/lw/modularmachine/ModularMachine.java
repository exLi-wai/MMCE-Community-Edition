package com.lw.modularmachine;

import com.lw.modularmachine.command.MachineCommands;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.config.MachineStartupConfig;
import com.lw.modularmachine.item.WrenchInteractions;
import com.lw.modularmachine.machine.assembly.AssemblyInteractions;
import com.lw.modularmachine.machine.assembly.AssemblyService;
import com.lw.modularmachine.machine.appearance.MachineComponentVisibility;
import com.lw.modularmachine.machine.controller.DedicatedControllerResources;
import com.lw.modularmachine.machine.factory.FactoryUpgradeService;
import com.lw.modularmachine.machine.recipe.MachineRecipeRegistries;
import com.lw.modularmachine.machine.recipe.MachineRecipeService;
import com.lw.modularmachine.machine.recipe.RecipeLifecyclePublisher;
import com.lw.modularmachine.machine.smart.SmartInterfaceEventPublisher;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.network.GroupInputPayloads;
import com.lw.modularmachine.network.BlueprintPreviewPayloads;
import com.lw.modularmachine.network.RangedSelectionPayloads;
import com.lw.modularmachine.machine.selection.RangedSelectionService;
import com.lw.modularmachine.network.MachineJeiPayloads;
import com.lw.modularmachine.network.MachineColorPayloads;
import com.lw.modularmachine.network.MachineVisibilityPayloads;
import com.lw.modularmachine.network.MachinePayloads;
import com.lw.modularmachine.network.ParallelControllerPayloads;
import com.lw.modularmachine.network.ProjectionPayloads;
import com.lw.modularmachine.network.SmartInterfacePayloads;
import com.lw.modularmachine.network.UpgradeBusPayloads;
import com.lw.modularmachine.registry.MachineCapabilities;
import com.lw.modularmachine.registry.MachineDataComponents;
import com.lw.modularmachine.registry.MachineRegistries;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

@Mod(ModularMachine.MODID)
public class ModularMachine {

  public static final String MODID = "mmcece";
  public static final Logger LOGGER = LogUtils.getLogger();
  public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
  public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
  public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister
      .create(Registries.CREATIVE_MODE_TAB, MODID);

  public ModularMachine(IEventBus modEventBus, ModContainer modContainer) {
    modContainer.registerConfig(ModConfig.Type.STARTUP, MachineStartupConfig.SPEC, MachineStartupConfig.FILE_NAME);
    MachineStartupConfig.enabledAtStartup();
    modEventBus.addListener(this::commonSetup);
    modEventBus.addListener(DedicatedControllerResources::register);
    modEventBus.addListener(ProjectionPayloads::register);
    modEventBus.addListener(BlueprintPreviewPayloads::register);
    modEventBus.addListener(RangedSelectionPayloads::register);
    modEventBus.addListener(MachinePayloads::register);
    modEventBus.addListener(UpgradeBusPayloads::register);
    modEventBus.addListener(SmartInterfacePayloads::register);
    modEventBus.addListener(MachineJeiPayloads::register);
    modEventBus.addListener(MachineColorPayloads::register);
    modEventBus.addListener(MachineVisibilityPayloads::register);
    modEventBus.addListener(ParallelControllerPayloads::register);
    modEventBus.addListener(GroupInputPayloads::register);
    BLOCKS.register(modEventBus);
    ITEMS.register(modEventBus);
    CREATIVE_MODE_TABS.register(modEventBus);
    MachineDataComponents.register(modEventBus);
    MachineRegistries.register(modEventBus);
    MachineRecipeRegistries.register(modEventBus);
    modEventBus.addListener(MachineCapabilities::register);
    modContainer.registerConfig(ModConfig.Type.COMMON, MachineConfig.SPEC, "modularmachinery/mmcece-common.toml");
    NeoForge.EVENT_BUS.register(AssemblyService.class);
    NeoForge.EVENT_BUS.register(RangedSelectionService.class);
    NeoForge.EVENT_BUS.register(StructureService.class);
    NeoForge.EVENT_BUS.register(MachineComponentVisibility.class);
    NeoForge.EVENT_BUS.register(MachineRecipeService.class);
    NeoForge.EVENT_BUS.register(MachineJeiPayloads.class);
    NeoForge.EVENT_BUS.register(FactoryUpgradeService.class);
    NeoForge.EVENT_BUS.register(SmartInterfaceEventPublisher.class);
    NeoForge.EVENT_BUS.register(RecipeLifecyclePublisher.class);
    NeoForge.EVENT_BUS.register(AssemblyInteractions.class);
    NeoForge.EVENT_BUS.register(WrenchInteractions.class);
    NeoForge.EVENT_BUS.register(MachineCommands.class);
    NeoForge.EVENT_BUS.register(this);
  }

  private void commonSetup(FMLCommonSetupEvent event) {
  }

  @SubscribeEvent
  public void onServerStarting(ServerStartingEvent event) {
  }
}
