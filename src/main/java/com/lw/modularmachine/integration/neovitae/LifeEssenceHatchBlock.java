package com.lw.modularmachine.integration.neovitae;

import com.lw.modularmachine.block.TickingRecipeHatchBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class LifeEssenceHatchBlock extends TickingRecipeHatchBlock {
  public LifeEssenceHatchBlock(Properties properties) {
    super(properties.noOcclusion(), LifeEssenceHatchBlockEntity::new);
  }

  @Override
  protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit) {
    if (!level.isClientSide && level.getBlockEntity(position) instanceof LifeEssenceHatchBlockEntity hatch) {
      hatch.openMenu(player);
    }
    return InteractionResult.sidedSuccess(level.isClientSide);
  }

  @Override
  protected void onRemove(BlockState state, Level level, BlockPos position, BlockState replacement, boolean moving) {
    if (!level.isClientSide
        && !state.is(replacement.getBlock())
        && level.getBlockEntity(position) instanceof LifeEssenceHatchBlockEntity hatch) {
      var orb = hatch.removeForDrop();
      if (!orb.isEmpty()) {
        Containers.dropItemStack(level, position.getX(), position.getY(), position.getZ(), orb);
      }
    }
    super.onRemove(state, level, position, replacement, moving);
  }
}
