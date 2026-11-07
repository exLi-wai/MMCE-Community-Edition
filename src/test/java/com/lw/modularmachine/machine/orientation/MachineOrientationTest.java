package com.lw.modularmachine.machine.orientation;

import com.lw.modularmachine.api.orientation.MachineOrientation;

import com.lw.modularmachine.machine.structure.CompiledStructure;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MachineOrientationTest {
  private static final Direction[] FRONTS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST,
      Direction.UP, Direction.DOWN};
  private static final Direction[][] EXPECTED_UP = {{Direction.UP, Direction.WEST, Direction.DOWN, Direction.EAST},
      {Direction.UP, Direction.NORTH, Direction.DOWN, Direction.SOUTH},
      {Direction.UP, Direction.EAST, Direction.DOWN, Direction.WEST},
      {Direction.UP, Direction.SOUTH, Direction.DOWN, Direction.NORTH},
      {Direction.SOUTH, Direction.WEST, Direction.NORTH, Direction.EAST},
      {Direction.NORTH, Direction.WEST, Direction.SOUTH, Direction.EAST}};

  // Literal world positions for local (2, 3, 5). Do not derive these with the
  // production transform.
  private static final int[][] EXPECTED_POINT = {{2, 3, 5}, {-3, 2, 5}, {-2, -3, 5}, {3, -2, 5}, {-5, 3, 2},
      {-5, 2, -3}, {-5, -3, -2}, {-5, -2, 3}, {-2, 3, -5}, {3, 2, -5}, {2, -3, -5}, {-3, -2, -5}, {5, 3, -2}, {5, 2, 3},
      {5, -3, 2}, {5, -2, -3}, {2, -5, 3}, {-3, -5, 2}, {-2, -5, -3}, {3, -5, -2}, {2, 5, -3}, {-3, 5, -2}, {-2, 5, 3},
      {3, 5, 2}};

  @Test
  void allTwentyFourMatchIndependentBasisAndPositionFixtures() {
    BlockPos local = new BlockPos(2, 3, 5);
    for (int f = 0; f < FRONTS.length; f++) {
      for (int roll = 0; roll < 4; roll++) {
        MachineOrientation orientation = MachineOrientation.of(FRONTS[f], roll);
        assertSame(EXPECTED_UP[f][roll], orientation.up(), orientation.key());
        int[] expected = EXPECTED_POINT[f * 4 + roll];
        assertEquals(
            new BlockPos(expected[0], expected[1], expected[2]),
            orientation.toWorldOffset(local),
            orientation.key());
        assertSame(orientation, MachineOrientation.fromFrontAndUp(FRONTS[f], EXPECTED_UP[f][roll]));
      }
    }
  }

  @Test
  void basesAreDistinctOrthogonalAndRightHanded() {
    Set<List<Direction>> bases = new HashSet<>();
    for (MachineOrientation orientation : MachineOrientation.all()) {
      Direction right = orientation.right(), up = orientation.up(), back = orientation.back();
      assertEquals(0, dot(right, up));
      assertEquals(0, dot(right, back));
      assertEquals(0, dot(up, back));
      int determinant = right.getStepX() * (up.getStepY() * back.getStepZ() - up.getStepZ() * back.getStepY()) - up
          .getStepX() * (right.getStepY() * back.getStepZ() - right.getStepZ() * back.getStepY()) + back
              .getStepX() * (right.getStepY() * up.getStepZ() - right.getStepZ() * up.getStepY());
      assertEquals(1, determinant, orientation.key());
      assertTrue(bases.add(List.of(right, up, back)), orientation.key());
    }
    assertEquals(24, bases.size());
  }

  @Test
  void signedOffsetsRoundTripWithoutRetainingMutablePositions() {
    List<BlockPos> points = List
        .of(BlockPos.ZERO, new BlockPos(2, 3, 5), new BlockPos(-7, 11, -13), new BlockPos(128, -128, 0));
    for (MachineOrientation orientation : MachineOrientation.all()) {
      for (BlockPos point : points) {
        assertEquals(point, orientation.toLocalOffset(orientation.toWorldOffset(point)), orientation.key());
        assertEquals(point, orientation.toWorldOffset(orientation.toLocalOffset(point)), orientation.key());
      }
      BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(2, 3, 5);
      BlockPos transformed = orientation.toWorldOffset(mutable);
      assertFalse(transformed instanceof BlockPos.MutableBlockPos);
      assertEquals(new BlockPos(2, 3, 5), mutable);
      mutable.set(9, 8, 7);
      assertEquals(orientation.toWorldOffset(new BlockPos(2, 3, 5)), transformed);
    }
  }

  @Test
  void directionsAndUnsignedAxesAgreeWithOffsetTransforms() {
    for (MachineOrientation orientation : MachineOrientation.all()) {
      for (Direction direction : Direction.values()) {
        Direction world = orientation.toWorldDirection(direction);
        assertSame(direction, orientation.toLocalDirection(world));
        assertEquals(unit(world), orientation.toWorldOffset(unit(direction)));
        assertSame(world.getAxis(), orientation.toWorldAxis(direction.getAxis()));
        assertSame(direction.getAxis(), orientation.toLocalAxis(world.getAxis()));
      }
      for (Direction.Axis axis : Direction.Axis.values()) {
        assertSame(axis, orientation.toLocalAxis(orientation.toWorldAxis(axis)));
      }
    }
  }

  @Test
  void allFiveHundredSeventySixCompositionsApplyTheArgumentFirst() {
    BlockPos point = new BlockPos(-7, 11, -13);
    for (MachineOrientation after : MachineOrientation.all()) {
      for (MachineOrientation before : MachineOrientation.all()) {
        MachineOrientation combined = after.compose(before);
        assertSame(combined, MachineOrientation.of(combined.front(), combined.roll()));
        assertEquals(after.toWorldOffset(before.toWorldOffset(point)), combined.toWorldOffset(point));
        for (Direction direction : Direction.values()) {
          assertSame(after.toWorldDirection(before.toWorldDirection(direction)), combined.toWorldDirection(direction));
        }
      }
    }
    MachineOrientation a = MachineOrientation.of(Direction.EAST, 0);
    MachineOrientation b = MachineOrientation.of(Direction.UP, 0);
    assertNotSame(a.compose(b), b.compose(a), "Fixture must distinguish composition order");
  }

  @Test
  void inverseComposesToIdentityOnBothSides() {
    for (MachineOrientation orientation : MachineOrientation.all()) {
      assertSame(MachineOrientation.IDENTITY, orientation.compose(orientation.inverse()));
      assertSame(MachineOrientation.IDENTITY, orientation.inverse().compose(orientation));
      assertSame(orientation, orientation.inverse().inverse());
      assertEquals(
          orientation.toLocalOffset(new BlockPos(2, 3, 5)),
          orientation.inverse().toWorldOffset(new BlockPos(2, 3, 5)));
    }
  }

  @Test
  void rollsAreClockwiseAndHandleNegativeAndExtremeTurnCounts() {
    for (MachineOrientation orientation : MachineOrientation.all()) {
      assertSame(orientation.right().getOpposite(), orientation.rollBy(1).up());
      assertSame(orientation.up(), orientation.rollBy(1).right());
      assertSame(orientation, orientation.rollBy(1).rollBy(1).rollBy(1).rollBy(1));
      assertSame(orientation, orientation.rollBy(-1).rollBy(1));
      assertSame(orientation, orientation.rollBy(Integer.MIN_VALUE));
      assertSame(orientation.rollBy(-1), orientation.rollBy(Integer.MAX_VALUE));
      assertSame(orientation.front(), orientation.rollBy(1).front());
    }
    assertSame(MachineOrientation.of(Direction.NORTH, 3), MachineOrientation.IDENTITY.rollBy(-1));
  }

  @Test
  void changingFrontPreservesWorldUpOrUsesTheExplicitZeroRoll() {
    for (MachineOrientation orientation : MachineOrientation.all()) {
      assertSame(orientation, orientation.withFront(orientation.front()));
      for (Direction newFront : Direction.values()) {
        MachineOrientation result = orientation.withFront(newFront);
        assertSame(newFront, result.front());
        if (newFront.getAxis() == orientation.up().getAxis()) {
          assertSame(MachineOrientation.of(newFront, 0), result);
        } else {
          assertSame(orientation.up(), result.up());
        }
      }
    }
    assertSame(
        MachineOrientation.of(Direction.SOUTH, 3),
        MachineOrientation.of(Direction.NORTH, 1).withFront(Direction.SOUTH));
    assertSame(MachineOrientation.of(Direction.UP, 0), MachineOrientation.IDENTITY.withFront(Direction.UP));
    assertSame(MachineOrientation.of(Direction.DOWN, 0), MachineOrientation.IDENTITY.withFront(Direction.DOWN));
  }

  @Test
  void illegalInputsAreRejectedInsteadOfWrappedOrGuessed() {
    assertThrows(IllegalArgumentException.class, () -> MachineOrientation.of(Direction.NORTH, -1));
    assertThrows(IllegalArgumentException.class, () -> MachineOrientation.of(Direction.NORTH, 4));
    for (Direction direction : Direction.values()) {
      assertThrows(IllegalArgumentException.class, () -> MachineOrientation.fromFrontAndUp(direction, direction));
      assertThrows(
          IllegalArgumentException.class,
          () -> MachineOrientation.fromFrontAndUp(direction, direction.getOpposite()));
    }
    assertThrows(NullPointerException.class, () -> MachineOrientation.of(null, 0));
    assertThrows(NullPointerException.class, () -> MachineOrientation.fromFrontAndUp(Direction.NORTH, null));
    assertThrows(NullPointerException.class, () -> MachineOrientation.IDENTITY.withFront(null));
    assertThrows(NullPointerException.class, () -> MachineOrientation.IDENTITY.compose(null));
    assertThrows(NullPointerException.class, () -> MachineOrientation.IDENTITY.toWorldOffset(null));
    assertThrows(NullPointerException.class, () -> MachineOrientation.IDENTITY.toLocalOffset(null));
    assertThrows(NullPointerException.class, () -> MachineOrientation.IDENTITY.toWorldDirection(null));
    assertThrows(NullPointerException.class, () -> MachineOrientation.IDENTITY.toLocalDirection(null));
    assertThrows(NullPointerException.class, () -> MachineOrientation.IDENTITY.toWorldAxis(null));
    assertThrows(NullPointerException.class, () -> MachineOrientation.IDENTITY.toLocalAxis(null));
  }

  @Test
  void canonicalOrderKeysAndMasksAreStableAndTheCatalogIsReadOnly() {
    String[] names = {"north", "east", "south", "west", "up", "down"};
    assertEquals(24, MachineOrientation.all().size());
    int mask = 0;
    for (int f = 0; f < names.length; f++) {
      for (int roll = 0; roll < 4; roll++) {
        MachineOrientation orientation = MachineOrientation.of(FRONTS[f], roll);
        int index = f * 4 + roll;
        assertSame(orientation, MachineOrientation.all().get(index));
        assertEquals(index, orientation.stableIndex());
        assertEquals(names[f] + "_" + roll, orientation.key());
        assertEquals(orientation.key(), orientation.toString());
        assertEquals(0, mask & orientation.maskBit());
        mask |= orientation.maskBit();
      }
    }
    assertEquals(0xFFFFFF, mask);
    assertEquals(MachineOrientation.ALL_MASK, mask);
    assertSame(MachineOrientation.of(Direction.NORTH, 0), MachineOrientation.IDENTITY);
    assertThrows(UnsupportedOperationException.class, () -> MachineOrientation.all().clear());
  }

  @Test
  void theExistingHorizontalEntryPointRetainsLiteralLegacyCoordinates() {
    BlockPos point = new BlockPos(2, 3, 5);
    BlockPos[] expected = {new BlockPos(2, 3, 5), new BlockPos(-5, 3, 2), new BlockPos(-2, 3, -5),
        new BlockPos(5, 3, -2)};
    for (int i = 0; i < 4; i++) {
      assertEquals(expected[i], CompiledStructure.rotate(point, FRONTS[i]));
    }
    assertThrows(IllegalArgumentException.class, () -> CompiledStructure.rotate(point, Direction.UP));
    assertThrows(IllegalArgumentException.class, () -> CompiledStructure.rotate(point, Direction.DOWN));
  }

  @Test
  void anUnrepresentableIntegerNegationFailsInsteadOfWrappingCoordinates() {
    BlockPos minX = new BlockPos(Integer.MIN_VALUE, 0, 0);
    assertEquals(minX, MachineOrientation.IDENTITY.toWorldOffset(minX));
    MachineOrientation reversed = MachineOrientation.of(Direction.SOUTH, 0);
    assertThrows(ArithmeticException.class, () -> reversed.toWorldOffset(minX));
    assertThrows(ArithmeticException.class, () -> reversed.toLocalOffset(minX));
  }

  private static int dot(Direction first, Direction second) {
    return first.getStepX() * second.getStepX() + first.getStepY() * second.getStepY() + first.getStepZ() * second
        .getStepZ();
  }

  private static BlockPos unit(Direction direction) {
    return new BlockPos(direction.getStepX(), direction.getStepY(), direction.getStepZ());
  }
}
