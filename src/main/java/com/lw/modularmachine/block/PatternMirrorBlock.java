package com.lw.modularmachine.block;

import com.lw.modularmachine.blockentity.PatternMirrorBlockEntity;
import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

public final class PatternMirrorBlock extends BaseEntityBlock {
  public static final MapCodec<PatternMirrorBlock> CODEC = simpleCodec(PatternMirrorBlock::new);
  public PatternMirrorBlock(Properties properties) {
    super(properties);
  }
  @Override
  protected MapCodec<? extends BaseEntityBlock> codec() {
    return CODEC;
  }
  @Override
  protected RenderShape getRenderShape(BlockState state) {
    return RenderShape.MODEL;
  }
  @Override
  public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
    return new PatternMirrorBlockEntity(pos, state);
  }
  @Override
  protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
    var drops = super.getDrops(state, params);
    if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof PatternMirrorBlockEntity mirror) {
      for (var stack : drops) {
        if (stack.is(asItem())) {
          BlockItem.setBlockEntityData(
              stack,
              mirror.getType(),
              mirror.saveWithoutMetadata(params.getLevel().registryAccess()));
        }
      }
    }
    return drops;
  }
}
