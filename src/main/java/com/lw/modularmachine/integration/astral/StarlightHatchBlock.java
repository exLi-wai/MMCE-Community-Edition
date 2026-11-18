package com.lw.modularmachine.integration.astral;

import com.lw.modularmachine.block.TickingRecipeHatchBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class StarlightHatchBlock extends TickingRecipeHatchBlock {
  public StarlightHatchBlock(Properties properties, boolean input) {
    super(properties, input ? StarlightHatch.Input::new : StarlightHatch.Output::new);
  }

  @Override
  protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit) {
    if (!level.isClientSide && level.getBlockEntity(position) instanceof StarlightHatch<?> hatch) {
      hatch.openMenu(player);
    }
    return InteractionResult.sidedSuccess(level.isClientSide);
  }

  @Override
  protected void onRemove(BlockState state, Level level, BlockPos position, BlockState replacement, boolean moving) {
    if (!state.is(replacement.getBlock()) && level.getBlockEntity(position) instanceof StarlightHatch<?> hatch) {
      hatch.onTileEntityRemove(level, position);
    }
    super.onRemove(state, level, position, replacement, moving);
  }
}
