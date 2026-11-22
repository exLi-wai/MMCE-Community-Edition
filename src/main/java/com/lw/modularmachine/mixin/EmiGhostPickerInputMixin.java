package com.lw.modularmachine.mixin;

import com.lw.modularmachine.client.NetworkPortScreen;
import dev.emi.emi.runtime.EmiDrawContext;
import dev.emi.emi.screen.EmiScreenBase;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "dev.emi.emi.screen.EmiScreenManager", remap = false)
public abstract class EmiGhostPickerInputMixin {
  @Inject(method = "keyPressed(III)Z", at = @At("HEAD"), cancellable = true)
  private static void MMCECE$handlePickerKey(int key, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> callback) {
    if (Minecraft.getInstance().screen instanceof NetworkPortScreen screen && screen.hasGhostPicker()) {
      callback.setReturnValue(screen.keyPressed(key, scanCode, modifiers));
    }
  }

  @Inject(method = "renderCurrentTooltip", at = @At("HEAD"), cancellable = true)
  private static void MMCECE$hideCoveredTooltip(EmiDrawContext context, int mouseX, int mouseY, float delta, EmiScreenBase base, CallbackInfo callback) {
    if (Minecraft.getInstance().screen instanceof NetworkPortScreen screen
        && screen.isOverGhostPicker(mouseX, mouseY)) {
      callback.cancel();
    }
  }
}
