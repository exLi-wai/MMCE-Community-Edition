package com.lw.modularmachine.item;

import com.lw.modularmachine.block.FactoryControllerBlock;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.machine.controller.DedicatedControllerCatalog;
import com.lw.modularmachine.machine.orientation.ControllerPlacementData;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.registry.MachineRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A real registered block item whose identity cannot be rebound through
 * portable NBT.
 */
public final class DedicatedControllerItem extends ControllerBlockItem {
  private final DedicatedControllerCatalog.Entry entry;
  public DedicatedControllerItem(Block block, Properties properties, DedicatedControllerCatalog.Entry entry) {
    super(block, properties);
    this.entry = entry;
  }
  public DedicatedControllerCatalog.Entry entry() {
    return entry;
  }
  @Override
  public Component getName(ItemStack stack) {
    return DedicatedControllerCatalog.displayName(entry, getDescriptionId());
  }
  @Override
  protected BlockState getPlacementState(BlockPlaceContext context) {
    var state = super.getPlacementState(context);
    if (state == null) {
      return null;
    }
    var carried = context.getItemInHand().get(DataComponents.BLOCK_STATE);
    if (carried != null) {
      state = carried.apply(state);
    }
    if (!context.getLevel().isClientSide) {
      var restoredMachine = entry.machine();
      var portable = context.getItemInHand().get(DataComponents.BLOCK_ENTITY_DATA);
      if (portable != null) {
        var root = portable.copyTag();
        var expected = entry.factory() ? MachineRegistries.FACTORY_CONTROLLER_ENTITY
            .get() : MachineRegistries.CONTROLLER_ENTITY.get();
        if (!root.contains("id", Tag.TAG_STRING)
            || !BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(expected).toString().equals(root.getString("id"))) {
          return null;
        }
        if (entry.factory()) {
          if (!root.contains("machine", Tag.TAG_STRING)
              || ResourceLocation.tryParse(root.getString("machine")) == null) {
            return null;
          }
          restoredMachine = ResourceLocation.parse(root.getString("machine"));
        } else {
          var binding = ControllerPlacementData.fromTag(root);
          if (binding.opaque()) {
            return null;
          }
          restoredMachine = binding.machineId();
        }
      }
      if (restoredMachine.equals(ControllerPlacementData.UNBOUND_MACHINE)) {
        restoredMachine = entry.machine();
      }
      var definition = StructureService.definition(restoredMachine);
      var pose = entry.factory() ? MachineOrientation.of(
          state.getValue(FactoryControllerBlock.FACING),
          state.getValue(FactoryControllerBlock.ROLL)) : MachineControllerBlock.orientationOf(state);
      if (definition == null || !definition.allows(pose)) {
        return null;
      }
    }
    return state;
  }
}
