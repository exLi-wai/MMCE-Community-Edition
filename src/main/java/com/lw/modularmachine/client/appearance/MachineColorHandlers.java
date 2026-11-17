package com.lw.modularmachine.client.appearance;

import com.lw.modularmachine.api.component.MachineColorable;
import com.lw.modularmachine.block.FactoryControllerBlock;
import com.lw.modularmachine.block.MachineCasingBlock;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.block.MachinePortBlock;
import com.lw.modularmachine.block.ParallelControllerBlock;
import com.lw.modularmachine.block.SmartInterfaceBlock;
import com.lw.modularmachine.block.UpgradeBusBlock;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.client.preview.PreviewBlockView;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.controller.DedicatedControllerCatalog;
import com.lw.modularmachine.machine.orientation.ControllerPlacementData;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.network.MachineColorPayloads;
import com.lw.modularmachine.registry.MachineRegistries;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.BlockAndTintGetter;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.block.ColoredStatedMachineComponentBlock;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Shared block/item tint registration and authoritative component color
 * updates.
 */
@ApiStatus.Internal
public final class MachineColorHandlers {
  private MachineColorHandlers() {
  }

  public static void registerBlocks(RegisterColorHandlersEvent.Block event) {
    event.register(MachineColorHandlers::blockColor, colorableBlocks().toArray(Block[]::new));
  }

  public static void registerItems(RegisterColorHandlersEvent.Item event) {
    event.register(MachineColorHandlers::itemColor, colorableBlocks().stream().map(Block::asItem).toArray(Item[]::new));
  }

  private static List<Block> colorableBlocks() {
    var blocks = new ArrayList<Block>();
    blocks.add(MachineRegistries.CONTROLLER.get());
    blocks.add(MachineRegistries.FACTORY_CONTROLLER.get());
    MachineRegistries.DEDICATED_CONTROLLERS.values().forEach(holder -> blocks.add(holder.get()));
    MachineRegistries.DEDICATED_FACTORIES.values().forEach(holder -> blocks.add(holder.get()));
    MachineRegistries.PORTS.values().forEach(holder -> blocks.add(holder.get()));
    MachineRegistries.NETWORK_PORTS.values().forEach(holder -> blocks.add(holder.get()));
    if (MachineRegistries.ME_PATTERN_PROVIDER != null) {
      blocks.add(MachineRegistries.ME_PATTERN_PROVIDER.get());
      blocks.add(MachineRegistries.ME_PATTERN_MIRROR.get());
    }
    MachineRegistries.CASINGS.values().forEach(holder -> blocks.add(holder.get()));
    MachineRegistries.PARALLEL_CONTROLLERS.values().forEach(holder -> blocks.add(holder.get()));
    MachineRegistries.UPGRADE_BUSES.values().forEach(holder -> blocks.add(holder.get()));
    blocks.add(MachineRegistries.SMART_INTERFACE.get());
    BuiltInRegistries.BLOCK.forEach(block -> {
      if (block instanceof ColoredStatedMachineComponentBlock) {
        blocks.add(block);
      }
    });
    return blocks;
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
    return entity instanceof MachineColorable colorable ? colorable.machineColor() : MachineConfig.defaultCasingColor();
  }

  private static boolean isMachineColorBlock(Block block) {
    return block instanceof MachineCasingBlock
        || block instanceof ColoredStatedMachineComponentBlock
        || block instanceof MachinePortBlock
        || block instanceof ParallelControllerBlock
        || block instanceof UpgradeBusBlock
        || block instanceof SmartInterfaceBlock
        || MachineRegistries.ME_PATTERN_PROVIDER != null && block == MachineRegistries.ME_PATTERN_PROVIDER.get()
        || MachineRegistries.ME_PATTERN_MIRROR != null && block == MachineRegistries.ME_PATTERN_MIRROR.get()
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
    return data.contains(MachineColorable.COLOR_TAG) ? MachineColorable.load(data) : MachineConfig.defaultCasingColor();
  }

  private static int machineColor(ResourceLocation machine) {
    var definition = StructureService.definition(machine);
    return definition == null ? DedicatedControllerCatalog.color(machine) : definition.color();
  }

  public static void receive(MachineColorPayloads.Update update) {
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
}
