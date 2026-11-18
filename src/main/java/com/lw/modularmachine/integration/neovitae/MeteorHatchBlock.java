package com.lw.modularmachine.integration.neovitae;

import com.lw.modularmachine.block.TickingRecipeHatchBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class MeteorHatchBlock extends TickingRecipeHatchBlock {
  public MeteorHatchBlock(Properties properties) {
    super(properties, MeteorHatchBlockEntity::new);
  }

  @Override
  protected void onRemove(BlockState state, Level level, BlockPos position, BlockState replacement, boolean moving) {
    if (level instanceof ServerLevel server
        && !state.is(replacement.getBlock())
        && server.getBlockEntity(position) instanceof MeteorHatchBlockEntity hatch) {
      hatch.transferLaunch(server);
    }
    super.onRemove(state, level, position, replacement, moving);
  }
}
