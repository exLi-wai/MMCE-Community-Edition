package com.lw.modularmachine.block;

import com.lw.modularmachine.blockentity.LaserInputHatchBlockEntity;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class LaserInputHatchBlock extends MachineCasingBlock {
  private static final MapCodec<LaserInputHatchBlock> CODEC = simpleCodec(LaserInputHatchBlock::new);

  public LaserInputHatchBlock(Properties properties) {
    super(properties);
  }

  @Override
  protected MapCodec<LaserInputHatchBlock> codec() {
    return CODEC;
  }

  @Override
  public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
    return new LaserInputHatchBlockEntity(pos, state);
  }
}
