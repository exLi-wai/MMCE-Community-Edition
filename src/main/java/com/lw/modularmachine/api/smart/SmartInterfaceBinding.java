package com.lw.modularmachine.api.smart;

import com.lw.modularmachine.machine.recipe.ResourceSelector;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** Immutable binding value supplied by smart interface queries and events. */
public record SmartInterfaceBinding(
    BlockPos controller,
    ResourceLocation machine,
    String type,
    double value,
    long revision) {
  public SmartInterfaceBinding {
    controller = controller.immutable();
    ResourceSelector.require(type);
    if (machine == null
        || machine.toString().length() > 256
        || type.isEmpty()
        || !Double.isFinite(value)
        || revision < 0) {
      throw new IllegalArgumentException("Invalid numerical interface binding");
    }
  }
}
