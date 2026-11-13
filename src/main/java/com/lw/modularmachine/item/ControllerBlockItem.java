package com.lw.modularmachine.item;

import com.lw.modularmachine.machine.controller.ControllerOwnershipService;
import com.lw.modularmachine.machine.recipe.command.ControllerCommandState;
import com.lw.modularmachine.machine.recipe.PatternRunSources;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Shared owner validation before the world or carried stack is changed. */
public class ControllerBlockItem extends BlockItem {
  public ControllerBlockItem(Block block, Properties properties) {
    super(block, properties);
  }

  @Override
  protected BlockState getPlacementState(BlockPlaceContext context) {
    var data = context.getItemInHand().get(DataComponents.BLOCK_ENTITY_DATA);
    if (data != null
        && data.copyTag().contains(PatternRunSources.NBT_KEY)
        && !PatternRunSources.load(data.copyTag().get(PatternRunSources.NBT_KEY)).valid()) {
      return null;
    }
    return ControllerOwnershipService.validatePlacement(context) ? super.getPlacementState(context) : null;
  }

  @Override
  protected boolean updateCustomBlockEntityTag(BlockPos pos, Level level, Player player, ItemStack stack, BlockState state) {
    var component = stack.get(DataComponents.BLOCK_ENTITY_DATA);
    if (component == null || !component.copyTag().contains(ControllerCommandState.NBT_KEY)) {
      return super.updateCustomBlockEntityTag(pos, level, player, stack, state);
    }
    var root = component.copyTag();
    ControllerCommandState.sanitizeItemData(root);
    var sanitized = stack.copy();
    sanitized.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(root));

    return super.updateCustomBlockEntityTag(pos, level, player, sanitized, state);
  }
}
