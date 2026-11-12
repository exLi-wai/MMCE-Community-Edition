package com.lw.modularmachine.machine.orientation;

import com.lw.modularmachine.api.orientation.MachineOrientation;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ControllerInteractionPolicyTest {
  @Test
  void frontClicksRollClockwiseAndSneakingReversesTheSameAxis() {
    assertSame(
        MachineOrientation.of(Direction.NORTH, 1),
        ControllerOrientationActions.wrenchTarget(MachineOrientation.IDENTITY, Direction.NORTH, false));
    assertSame(
        MachineOrientation.of(Direction.NORTH, 3),
        ControllerOrientationActions.wrenchTarget(MachineOrientation.IDENTITY, Direction.NORTH, true));
    for (var orientation : MachineOrientation.all()) {
      assertSame(
          orientation.rollBy(1),
          ControllerOrientationActions.wrenchTarget(orientation, orientation.front(), false));
      assertSame(
          orientation.rollBy(-1),
          ControllerOrientationActions.wrenchTarget(orientation, orientation.front(), true));
    }
  }

  @Test
  void otherFacesSetFrontAndKeepWorldUpWheneverPerpendicular() {
    for (var orientation : MachineOrientation.all()) {
      for (var face : Direction.values()) {
        if (face == orientation.front()) {
          continue;
        }
        var target = ControllerOrientationActions.wrenchTarget(orientation, face, false);
        assertSame(face, target.front());
        assertSame(target, ControllerOrientationActions.wrenchTarget(orientation, face, true));
        if (face.getAxis() != orientation.up().getAxis()) {
          assertSame(orientation.up(), target.up());
        } else {
          assertEquals(0, target.roll());
        }
      }
    }
  }

  @Test
  void horizontalPlacementKeepsLegacyOppositeFrontAndRollZero() {
    for (var direction : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
      var result = ControllerOrientationActions.placement(direction, direction, MachineOrientation.ALL_MASK)
          .orElseThrow();
      assertSame(direction.getOpposite(), result.front());
      assertSame(Direction.UP, result.up());
      assertEquals(0, result.roll());
    }
  }

  @Test
  void floorAndCeilingPlacementUsePlayerYawAsLocalUp() {
    Direction[] yaw = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    int[] floorRoll = {2, 3, 0, 1}, ceilingRoll = {0, 3, 2, 1};
    for (int i = 0; i < yaw.length; i++) {
      assertSame(
          MachineOrientation.of(Direction.UP, floorRoll[i]),
          ControllerOrientationActions.placement(Direction.DOWN, yaw[i], MachineOrientation.ALL_MASK).orElseThrow());
      assertSame(
          MachineOrientation.of(Direction.DOWN, ceilingRoll[i]),
          ControllerOrientationActions.placement(Direction.UP, yaw[i], MachineOrientation.ALL_MASK).orElseThrow());
    }
  }

  @Test
  void forbiddenVerticalPlacementFallsBackToTheSpecifiedHorizontalPoseOnly() {
    int horizontal = OrientationPolicy.HORIZONTAL.requestedMask();
    assertSame(
        MachineOrientation.of(Direction.WEST, 0),
        ControllerOrientationActions.placement(Direction.DOWN, Direction.EAST, horizontal).orElseThrow());
    assertSame(
        MachineOrientation.of(Direction.SOUTH, 0),
        ControllerOrientationActions.placement(Direction.UP, Direction.NORTH, horizontal).orElseThrow());
    assertTrue(
        ControllerOrientationActions
            .placement(Direction.DOWN, Direction.EAST, MachineOrientation.of(Direction.NORTH, 1).maskBit()).isEmpty());
    assertTrue(ControllerOrientationActions.placement(Direction.DOWN, Direction.NORTH, 0).isEmpty());
  }

  @Test
  void invalidInteractionInputsAreRejectedInsteadOfGuessed() {
    assertThrows(
        NullPointerException.class,
        () -> ControllerOrientationActions.wrenchTarget(null, Direction.NORTH, false));
    assertThrows(
        NullPointerException.class,
        () -> ControllerOrientationActions.wrenchTarget(MachineOrientation.IDENTITY, null, false));
    assertThrows(
        IllegalArgumentException.class,
        () -> ControllerOrientationActions.placement(Direction.DOWN, Direction.UP, MachineOrientation.ALL_MASK));
    assertThrows(
        IllegalArgumentException.class,
        () -> ControllerOrientationActions.placement(Direction.DOWN, Direction.NORTH, -1));
    assertThrows(
        NullPointerException.class,
        () -> ControllerOrientationActions.preferredPlacement(null, Direction.NORTH));
  }
}
