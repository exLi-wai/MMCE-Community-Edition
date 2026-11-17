package com.lw.modularmachine.mixin;

import com.lw.modularmachine.machine.structure.StructureChangeRouter;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * One dirty-notification path covers both instance and static
 * BlockEntity.setChanged.
 */
@Mixin(Level.class)
public abstract class LevelBlockEntityChangeMixin {
  @Inject(method = "blockEntityChanged", at = @At("RETURN"))
  private void MMCECE$blockEntityChanged(BlockPos position, CallbackInfo callback) {
    if ((Object) this instanceof ServerLevel level && level.getServer().isSameThread()) {
      StructureChangeRouter.blockEntityChanged(level, position);
    }
  }
}
