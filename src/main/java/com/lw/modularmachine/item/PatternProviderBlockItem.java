package com.lw.modularmachine.item;

import com.lw.modularmachine.blockentity.PatternProviderBlockEntity;
import com.lw.modularmachine.machine.network.pattern.PatternProviderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Never carry CPU identity or paid-run holds through vanilla's recursive NBT
 * merge.
 */
public final class PatternProviderBlockItem extends BlockItem {
  public PatternProviderBlockItem(Block block, Properties properties) {
    super(block, properties);
  }
  @Override
  protected BlockState getPlacementState(BlockPlaceContext context) {
    var data = context.getItemInHand().get(DataComponents.BLOCK_ENTITY_DATA);
    if (data != null
        && data.copyTag().contains(PatternProviderBlockEntity.DATA_TAG)
        && !PatternProviderBlockEntity.completeData(data.copyTag(), context.getLevel().registryAccess())) {
      return null;
    }
    return super.getPlacementState(context);
  }
  @Override
  protected boolean updateCustomBlockEntityTag(BlockPos pos, Level level, Player player, ItemStack stack, BlockState state) {
    var data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
    if (data == null) {
      return super.updateCustomBlockEntityTag(pos, level, player, stack, state);
    }
    var root = data.copyTag();
    root.put(PatternProviderBlockEntity.CONNECTION_TAG, new CompoundTag());
    if (root.contains(PatternProviderBlockEntity.DATA_TAG)) {
      if (!PatternProviderBlockEntity.completeData(root, level.registryAccess())) {
        return false;
      }
      var stored = root.getCompound(PatternProviderBlockEntity.DATA_TAG);
      var detached = PatternProviderState.load(stored.get("state"), level.registryAccess(), () -> {
      }, () -> {
      });
      stored.put("state", detached.portableSave());
    }
    var sanitized = stack.copy();
    sanitized.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(root));
    return super.updateCustomBlockEntityTag(pos, level, player, sanitized, state);
  }
}
