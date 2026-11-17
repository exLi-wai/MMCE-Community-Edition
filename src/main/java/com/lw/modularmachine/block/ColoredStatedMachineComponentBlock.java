package com.lw.modularmachine.block;

import com.lw.modularmachine.blockentity.StatedMachineComponentBlockEntity;
import com.mojang.serialization.MapCodec;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A separate entity-bearing block keeps uncoloured components free of block
 * entities.
 */
public final class ColoredStatedMachineComponentBlock extends StatedMachineComponentBlock implements EntityBlock {
  private final Supplier<BlockEntityType<?>> entityType;

  public ColoredStatedMachineComponentBlock(Properties properties, Supplier<BlockEntityType<?>> entityType) {
    super(properties);
    this.entityType = entityType;
  }

  @Override
  protected MapCodec<? extends StatedMachineComponentBlock> codec() {
    return MapCodec.unit(this);
  }

  @Override
  public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
    return new StatedMachineComponentBlockEntity(entityType.get(), position, state);
  }
}
