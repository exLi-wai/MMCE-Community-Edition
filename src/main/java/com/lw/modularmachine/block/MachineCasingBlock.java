package com.lw.modularmachine.block;

import com.lw.modularmachine.blockentity.MachineCasingBlockEntity;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class MachineCasingBlock extends BaseEntityBlock {
  public static final MapCodec<MachineCasingBlock> CODEC = simpleCodec(MachineCasingBlock::new);

  public MachineCasingBlock(Properties properties) {
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
    return new MachineCasingBlockEntity(pos, state);
  }

}
