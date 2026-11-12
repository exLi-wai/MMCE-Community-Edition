package com.lw.modularmachine.machine.orientation;

import com.lw.modularmachine.api.orientation.MachineOrientation;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.Direction;

/**
 * Shared pure placement/interaction geometry; permissions and definition
 * ownership stay on the server.
 */
public final class ControllerOrientationActions {
  private ControllerOrientationActions() {
  }

  public static MachineOrientation wrenchTarget(MachineOrientation current, Direction hitFace, boolean reverse) {
    Objects.requireNonNull(current, "current");
    Objects.requireNonNull(hitFace, "hitFace");

    return hitFace == current.front() ? current.rollBy(reverse ? -1 : 1) : current.withFront(hitFace);
  }

  public static MachineOrientation preferredPlacement(Direction lookDirection, Direction horizontalDirection) {
    Objects.requireNonNull(lookDirection, "lookDirection");
    Objects.requireNonNull(horizontalDirection, "horizontalDirection");
    if (horizontalDirection.getAxis() == Direction.Axis.Y) {
      throw new IllegalArgumentException("Expected horizontal player direction");
    }
    Direction front = lookDirection.getOpposite();

    return front.getAxis() == Direction.Axis.Y ? MachineOrientation
        .fromFrontAndUp(front, horizontalDirection) : MachineOrientation.of(front, 0);
  }

  public static Optional<MachineOrientation> placement(Direction lookDirection, Direction horizontalDirection, int allowedMask) {
    if ((allowedMask & ~MachineOrientation.ALL_MASK) != 0) {
      throw new IllegalArgumentException("Invalid orientation mask");
    }
    MachineOrientation preferred = preferredPlacement(lookDirection, horizontalDirection);
    if ((allowedMask & preferred.maskBit()) != 0) {
      return Optional.of(preferred);
    }
    MachineOrientation fallback = MachineOrientation.of(horizontalDirection.getOpposite(), 0);

    return (allowedMask & fallback.maskBit()) == 0 ? Optional.empty() : Optional.of(fallback);
  }
}
