package com.lw.modularmachine;

import com.lw.modularmachine.machine.orientation.ControllerPlacementData;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.Item;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.BlockPos;
import com.lw.modularmachine.client.*;
import com.lw.modularmachine.client.preview.BlueprintPreviewScreen;
import com.lw.modularmachine.client.preview.PreviewBlockView;
import com.lw.modularmachine.client.preview.WorldProjection;
import com.lw.modularmachine.block.FactoryControllerBlock;
import com.lw.modularmachine.block.MachineCasingBlock;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.block.MachinePortBlock;
import com.lw.modularmachine.block.ParallelControllerBlock;
import com.lw.modularmachine.block.SmartInterfaceBlock;
import com.lw.modularmachine.block.UpgradeBusBlock;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineColorable;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.config.MachineClientConfig;
import com.lw.modularmachine.integration.jei.ClientMachineJeiCatalog;
import com.lw.modularmachine.machine.blueprint.BlueprintPreviewActions;
import com.lw.modularmachine.menu.GroupInputMenu;
import com.lw.modularmachine.menu.ParallelControllerMenu;
import com.lw.modularmachine.menu.SmartInterfaceMenu;
import com.lw.modularmachine.network.*;
import com.lw.modularmachine.network.MachineColorPayloads;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.machine.controller.DedicatedControllerCatalog;
import com.lw.modularmachine.machine.structure.StructureService;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.item.ItemStack;
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
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

import java.util.ArrayList;

@Mod(value = ModularMachine.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = ModularMachine.MODID, value = Dist.CLIENT)
public class ModularMachineClient {
  public ModularMachineClient(ModContainer container) {
    container.registerConfig(ModConfig.Type.CLIENT, MachineClientConfig.SPEC, "modularmachinery/mmcece-client.toml");
    container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    MachineJeiPayloads.setReceiver(ClientMachineJeiCatalog::receive);
    ProjectionPayloads.setReceiver(WorldProjection::receive);
    MachineColorPayloads.setClientReceiver(ModularMachineClient::receiveMachineColor);
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
  static void registerBlockColors(RegisterColorHandlersEvent.Block event) {
    var blocks = new ArrayList<Block>();
    blocks.add(MachineRegistries.CONTROLLER.get());
    blocks.add(MachineRegistries.FACTORY_CONTROLLER.get());
    MachineRegistries.DEDICATED_CONTROLLERS.values().forEach(holder -> blocks.add(holder.get()));
    MachineRegistries.DEDICATED_FACTORIES.values().forEach(holder -> blocks.add(holder.get()));
    MachineRegistries.PORTS.values().forEach(holder -> blocks.add(holder.get()));
    MachineRegistries.CASINGS.values().forEach(holder -> blocks.add(holder.get()));
    MachineRegistries.PARALLEL_CONTROLLERS.values().forEach(holder -> blocks.add(holder.get()));
    MachineRegistries.UPGRADE_BUSES.values().forEach(holder -> blocks.add(holder.get()));
    blocks.add(MachineRegistries.SMART_INTERFACE.get());
    event.register(ModularMachineClient::blockColor, blocks.toArray(Block[]::new));
  }

  @SubscribeEvent
  static void registerItemColors(RegisterColorHandlersEvent.Item event) {
    var items = new ArrayList<Item>();
    items.add(MachineRegistries.CONTROLLER.get().asItem());
    items.add(MachineRegistries.FACTORY_CONTROLLER.get().asItem());
    MachineRegistries.DEDICATED_CONTROLLERS.values().forEach(holder -> items.add(holder.get().asItem()));
    MachineRegistries.DEDICATED_FACTORIES.values().forEach(holder -> items.add(holder.get().asItem()));
    MachineRegistries.PORTS.values().forEach(holder -> items.add(holder.get().asItem()));
    MachineRegistries.CASINGS.values().forEach(holder -> items.add(holder.get().asItem()));
    MachineRegistries.PARALLEL_CONTROLLERS.values().forEach(holder -> items.add(holder.get().asItem()));
    MachineRegistries.UPGRADE_BUSES.values().forEach(holder -> items.add(holder.get().asItem()));
    items.add(MachineRegistries.SMART_INTERFACE.get().asItem());
    event.register(ModularMachineClient::itemColor, items.toArray(Item[]::new));
  }

  private static int blockColor(BlockState state, BlockAndTintGetter level, BlockPos pos, int tintIndex) {
    if (tintIndex != 0) {
      return -1;
    }
    Block block = state.getBlock();
    int activePreviewColor = PreviewBlockView.activeMachineColor();
    if (activePreviewColor >= 0 && isMachineColorBlock(block)) {
      return activePreviewColor;
    }
    if (level instanceof PreviewBlockView preview && isMachineColorBlock(block)) {
      return preview.machineColor();
    }
    if (block instanceof MachineControllerBlock controller) {
      if (controller.dedicated() != null) {
        return machineColor(controller.dedicated().machine());
      }
      BlockEntity entity = pos == null ? null : level.getBlockEntity(pos);
      if (entity instanceof MachineControllerBlockEntity machine) {
        var definition = StructureService.definition(machine.machineId());
        return definition == null ? DedicatedControllerCatalog.color(machine.machineId()) : definition.color();
      }
    }
    if (block instanceof FactoryControllerBlock controller) {
      if (controller.dedicated() != null) {
        return machineColor(controller.dedicated().machine());
      }
      BlockEntity entity = pos == null ? null : level.getBlockEntity(pos);
      if (entity instanceof FactoryControllerBlockEntity machine) {
        var definition = StructureService.definition(machine.machineId());
        return definition == null ? DedicatedControllerCatalog.color(machine.machineId()) : definition.color();
      }
    }
    BlockEntity entity = pos == null ? null : level.getBlockEntity(pos);
    return entity instanceof MachineColorable colorable ? colorable.machineColor() : -1;
  }

  private static boolean isMachineColorBlock(Block block) {
    return block instanceof MachineCasingBlock
        || block instanceof MachinePortBlock
        || block instanceof ParallelControllerBlock
        || block instanceof UpgradeBusBlock
        || block instanceof SmartInterfaceBlock
        || block instanceof MachineControllerBlock
        || block instanceof FactoryControllerBlock;
  }

  private static int itemColor(ItemStack stack, int tintIndex) {
    if (tintIndex != 0) {
      return -1;
    }
    Block block = Block.byItem(stack.getItem());
    if (block instanceof MachineControllerBlock controller && controller.dedicated() != null) {
      return machineColor(controller.dedicated().machine());
    }
    if (block instanceof FactoryControllerBlock controller && controller.dedicated() != null) {
      return machineColor(controller.dedicated().machine());
    }
    var data = stack.getOrDefault(DataComponents.BLOCK_ENTITY_DATA, CustomData.EMPTY).copyTag();
    if (data.contains("machine")) {
      var machine = ResourceLocation.tryParse(data.getString("machine"));
      if (machine != null && !machine.equals(ControllerPlacementData.UNBOUND_MACHINE)) {
        return machineColor(machine);
      }
    }
    return data.contains(MachineColorable.COLOR_TAG) ? MachineColorable.load(data) : 0xFFFFFF;
  }

  private static int machineColor(ResourceLocation machine) {
    var definition = StructureService.definition(machine);
    return definition == null ? DedicatedControllerCatalog.color(machine) : definition.color();
  }

  private static void receiveMachineColor(MachineColorPayloads.Update update) {
    var level = Minecraft.getInstance().level;
    if (level == null
        || !level.dimension().location().equals(update.dimension())
        || !level.hasChunkAt(update.position())) {
      return;
    }
    if (level.getBlockEntity(update.position()) instanceof MachineColorable colorable) {
      colorable.setMachineColor(update.color());
    }
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
