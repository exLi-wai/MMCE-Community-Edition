package com.lw.modularmachine.machine.orientation;

import java.util.Objects;
import net.minecraft.core.Direction;

/**
 * Definition-level requested orientations; property representability may narrow
 * this set.
 */
public enum OrientationPolicy {
  HORIZONTAL("horizontal", horizontalMask()),
  ALL_24("all_24", MachineOrientation.ALL_MASK);

  private final String serializedName;
  private final int requestedMask;

  OrientationPolicy(String serializedName, int requestedMask) {
    this.serializedName = serializedName;
    this.requestedMask = requestedMask;
  }

  public String serializedName() {
    return serializedName;
  }

  public int requestedMask() {
    return requestedMask;
  }

  public boolean allows(MachineOrientation orientation) {
    return (requestedMask & Objects.requireNonNull(orientation, "orientation").maskBit()) != 0;
  }

  public static OrientationPolicy parse(String value) {
    for (OrientationPolicy policy : values()) {
      if (policy.serializedName.equals(value)) {
        return policy;
      }
    }
    throw new IllegalArgumentException("Unknown orientation policy: " + value);
  }

  private static int horizontalMask() {
    return MachineOrientation.of(Direction.NORTH, 0).maskBit() | MachineOrientation.of(Direction.EAST, 0)
        .maskBit() | MachineOrientation.of(Direction.SOUTH, 0)
            .maskBit() | MachineOrientation.of(Direction.WEST, 0).maskBit();
  }
}
