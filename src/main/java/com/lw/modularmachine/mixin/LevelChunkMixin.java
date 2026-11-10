package com.lw.modularmachine.mixin;

import com.lw.modularmachine.machine.structure.StructureService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Covers standard placement, removal, command, piston and fluid writes through
 * LevelChunk.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
  @Inject(method = "setBlockState", at = @At("RETURN"))
  private void MMCECE$blockChanged(BlockPos pos, BlockState state, boolean moving, CallbackInfoReturnable<BlockState> callback) {
    if (callback.getReturnValue() != null
        && ((LevelChunk) (Object) this).getLevel() instanceof ServerLevel level
        && level.getServer().isSameThread()) {
      StructureService.blockChanged(level, pos);
    }
  }
}
