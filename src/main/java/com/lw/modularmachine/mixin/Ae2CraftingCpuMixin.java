package com.lw.modularmachine.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.lw.modularmachine.integration.ae2.Ae2PatternJobs;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * AE 19.2.18: preserve CPU execution; observe delivery and terminal proof only.
 */
@Pseudo
@Mixin(targets = "appeng.crafting.execution.CraftingCpuLogic", remap = false)
public abstract class Ae2CraftingCpuMixin {
  @Shadow
  @Final
  private CraftingCPUCluster cluster;

  @Redirect(method = "executeCrafting", at = @At(value = "INVOKE", target = "Lappeng/api/networking/crafting/ICraftingProvider;pushPattern(Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z"))
  private boolean MMCECE$receivePattern(ICraftingProvider provider, IPatternDetails pattern, KeyCounter[] inputs) {
    return Ae2PatternJobs.push((CraftingCpuLogic) (Object) this, cluster, provider, pattern, inputs);
  }

  @Inject(method = "finishJob", at = @At("HEAD"))
  private void MMCECE$finishPatternOrder(boolean success, CallbackInfo callback) {
    Ae2PatternJobs.finished((CraftingCpuLogic) (Object) this, cluster, success);
  }

  @Inject(method = {"tickCraftingLogic", "readFromNBT"}, at = @At("RETURN"))
  private void MMCECE$associatePatternOrder(CallbackInfo callback) {
    Ae2PatternJobs.associated((CraftingCpuLogic) (Object) this, cluster);
  }
}
