package com.lw.modularmachine.item;

import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.machine.orientation.ControllerPlacementData;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.registry.MachineRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Validates the final item-carried state before vanilla writes it or consumes
 * the stack.
 */
public final class MachineControllerItem extends BlockItem {
  public MachineControllerItem(Block block, Properties properties) {
    super(block, properties);
  }

  @Override
  protected BlockState getPlacementState(BlockPlaceContext context) {
    BlockState state = super.getPlacementState(context);
    if (state == null) {
      return null;
    }
    var carriedState = context.getItemInHand().get(DataComponents.BLOCK_STATE);
    if (carriedState != null) {
      state = carriedState.apply(state);
    }
    if (!context.getLevel().isClientSide) {
      var component = context.getItemInHand().get(DataComponents.BLOCK_ENTITY_DATA);
      if (component != null) {
        var tag = component.copyTag();
        var expected = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(MachineRegistries.CONTROLLER_ENTITY.get());
        if (!tag.contains("id", Tag.TAG_STRING) || !expected.toString().equals(tag.getString("id"))) {
          if (context.getPlayer() != null) {
            context.getPlayer()
                .displayClientMessage(Component.translatable("message.mmcece.placement_invalid_data"), true);
          }
          return null;
        }
      }
      var binding = ControllerPlacementData.fromStack(context.getItemInHand());
      var definition = binding.opaque() ? null : StructureService.definition(binding.machineId());
      if (definition != null && !definition.allows(MachineControllerBlock.orientationOf(state))) {
        if (context.getPlayer() != null) {
          context.getPlayer()
              .displayClientMessage(Component.translatable("message.mmcece.placement_not_allowed"), true);
        }
        return null;
      }
    }
    return state;
  }
}
