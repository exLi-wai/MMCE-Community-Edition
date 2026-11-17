package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal main-thread lease bridge for resource participants in subpackages.
 * The caller must enlist the returned lease before writing, resolve it on every
 * exit and publish only after the enclosing transaction commits.
 */
@ApiStatus.Internal
public final class RecipeFluidLeases {
  private RecipeFluidLeases() {
  }

  public static MachinePortBlockEntity.FluidStartLease acquire(RecipePortSnapshot port) {
    if (port.patternInput()) {
      throw new IllegalArgumentException("Pattern fluids use their receipt transaction");
    }

    return switch (port.kind()) {
      case FLUID_INPUT -> port.identity().acquireFluidStart(port.revision(), port.tankIndex());
      case FLUID_OUTPUT -> port.identity().acquireFluidOutputStart(port.revision(), port.tankIndex());
      default -> throw new IllegalArgumentException("Fluid leases require a fluid input or output port");
    };
  }
}
