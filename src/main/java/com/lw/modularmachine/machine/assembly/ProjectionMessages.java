package com.lw.modularmachine.machine.assembly;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class ProjectionMessages {
  private ProjectionMessages() {
  }

  public static MutableComponent length(int layers) {
    return Component.translatable("message.mmcece.projection.length", layers);
  }

  static Component cancelled() {
    return Component.translatable("message.mmcece.projection.cancelled");
  }
}
