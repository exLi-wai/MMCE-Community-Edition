package com.lw.modularmachine.mixin;

import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.lw.modularmachine.integration.ae2.Ae2PatternJobs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Publishes already captured restore proof when the CPU gains a world owner.
 */
@Pseudo
@Mixin(targets = "appeng.me.cluster.implementations.CraftingCPUCluster", remap = false)
public abstract class Ae2CraftingClusterMixin {
  @Inject(method = "addBlockEntity", at = @At("RETURN"))
  private void MMCECE$associateRestoredPatternOrder(CallbackInfo callback) {
    var cluster = (CraftingCPUCluster) (Object) this;
    Ae2PatternJobs.associated(cluster.craftingLogic, cluster);
  }
}
