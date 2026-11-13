package com.lw.modularmachine.item;

import com.lw.modularmachine.blockentity.PatternMirrorBlockEntity;
import com.lw.modularmachine.machine.network.pattern.PatternProviderReference;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Validate the complete binding envelope before vanilla recursively merges it.
 */
public final class PatternMirrorBlockItem extends BlockItem {
  public PatternMirrorBlockItem(Block block, Properties properties) {
    super(block, properties);
  }
  @Override
  protected BlockState getPlacementState(BlockPlaceContext context) {
    var carried = context.getItemInHand().get(DataComponents.BLOCK_ENTITY_DATA);
    if (carried != null && carried.copyTag().contains(PatternMirrorBlockEntity.DATA_TAG)) {
      var root = carried.copyTag();
      var data = root.getCompound(PatternMirrorBlockEntity.DATA_TAG);
      if (!root.contains(PatternMirrorBlockEntity.DATA_TAG, Tag.TAG_COMPOUND)
          || !data.contains("schema", Tag.TAG_INT)
          || data.getInt("schema") != 1
          || !data.contains("revision", Tag.TAG_LONG)
          || data.getLong("revision") < 0) {
        return null;
      }
      if (data.contains("target")) {
        try {
          if (!data.contains("target", Tag.TAG_COMPOUND)) {
            return null;
          }
          PatternProviderReference.load(data.getCompound("target"));
        } catch (RuntimeException invalid) {
          return null;
        }
      }
    }
    return super.getPlacementState(context);
  }
}
