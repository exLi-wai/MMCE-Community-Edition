package com.lw.modularmachine.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public class TickingRecipeHatchBlock extends MachineCasingBlock {
  private final BlockEntityType.BlockEntitySupplier<?> factory;

  public TickingRecipeHatchBlock(Properties properties, BlockEntityType.BlockEntitySupplier<?> factory) {
    super(properties);
    this.factory = factory;
  }

  @Override
  public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
    return factory.create(position, state);
  }

  @Override
  public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
    return level.isClientSide() ? null : (world, position, current, entity) -> {
      if (entity instanceof ServerTicking hatch) {
        hatch.tickHatch();
      }
    };
  }

  public interface ServerTicking {
    void tickHatch();
  }
}
