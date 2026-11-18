package com.lw.modularmachine.block;

import com.lw.modularmachine.blockentity.EmberInputHatchBlockEntity;
import com.lw.modularmachine.machine.EmberHatchTier;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class EmberInputHatchBlock extends MachineCasingBlock {
  private final MapCodec<EmberInputHatchBlock> codec;
  private final EmberHatchTier tier;

  public EmberInputHatchBlock(Properties properties, EmberHatchTier tier) {
    super(properties);
    this.tier = tier;
    codec = simpleCodec(next -> new EmberInputHatchBlock(next, tier));
  }

  @Override
  protected MapCodec<EmberInputHatchBlock> codec() {
    return codec;
  }

  @Override
  public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
    return new EmberInputHatchBlockEntity(pos, state);
  }

  public EmberHatchTier tier() {
    return tier;
  }
}
