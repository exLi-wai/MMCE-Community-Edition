package com.lw.modularmachine.integration.thaumaturge;

import com.lw.modularmachine.block.TickingRecipeHatchBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class EssentiaHatchBlock extends TickingRecipeHatchBlock {
  public EssentiaHatchBlock(Properties properties, BlockEntityType.BlockEntitySupplier<?> factory) {
    super(properties, factory);
  }

  @Override
  protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit) {
    if (!player.isShiftKeyDown()) {
      return InteractionResult.PASS;
    }
    if (!level.isClientSide
        && !player.isSpectator()
        && player.mayBuild()
        && level.mayInteract(player, position)
        && level.getBlockEntity(position) instanceof EssentiaHatch hatch) {
      hatch.recoverContents();
    }
    return InteractionResult.sidedSuccess(level.isClientSide);
  }

  @Override
  protected void onRemove(BlockState state, Level level, BlockPos position, BlockState replacement, boolean moving) {
    if (!level.isClientSide
        && !state.is(replacement.getBlock())
        && level.getBlockEntity(position) instanceof EssentiaHatch hatch) {
      hatch.dropContents();
    }
    super.onRemove(state, level, position, replacement, moving);
  }
}
