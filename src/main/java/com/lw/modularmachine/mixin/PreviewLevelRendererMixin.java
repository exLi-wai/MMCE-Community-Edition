package com.lw.modularmachine.mixin;

import com.lw.modularmachine.client.preview.WorldProjection;
import com.lw.modularmachine.client.selection.RangedSelectionClient;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Client-only invalidation, including neighbor and chunk renderer updates. */
@Mixin(LevelRenderer.class)
public abstract class PreviewLevelRendererMixin {
  @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
  private void MMCECE$projectionChanged(int x, int y, int z, boolean immediate, CallbackInfo callback) {
    WorldProjection.sectionChanged(x, y, z);
    RangedSelectionClient.sectionChanged(x, y, z);
  }
}
