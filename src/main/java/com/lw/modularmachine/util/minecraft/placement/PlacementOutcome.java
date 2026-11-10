package com.lw.modularmachine.util.minecraft.placement;

import org.jetbrains.annotations.ApiStatus;

/** Internal placement results shared by execution and diagnostics. */
@ApiStatus.Internal
public enum PlacementOutcome {
  PLACED,
  MISSING,
  UNLOADED,
  OUTSIDE_WORLD,
  NO_PERMISSION,
  MANUAL,
  INVALID_STATE,
  SUPPORT,
  COLLISION,
  REJECTED,
  CHANGED,
  BUSY,
  FLUID_VAPORIZES,
  FLUID_CONTAINER,
  ERROR
}
