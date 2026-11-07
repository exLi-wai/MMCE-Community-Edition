package com.lw.modularmachine.api.orientation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * The 24 proper cube rotations, expressed in the machine's original local
 * frame: +X is east/right, +Y is up, and +Z is south/back. NORTH with roll zero
 * is identity. Instances are immutable and canonical; no world, registry, or
 * controller state is read.
 */
public final class MachineOrientation {
  private static final List<Direction> FRONT_ORDER = List
      .of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP, Direction.DOWN);
  private static final MachineOrientation[][] ORIENTATIONS = createOrientations();
  private static final List<MachineOrientation> ALL = createAll();

  public static final MachineOrientation IDENTITY = ORIENTATIONS[0][0];
  public static final int ALL_MASK = (1 << 24) - 1;

  private final Direction front;
  private final int roll;
  private final Direction up;
  private final Direction right;
  private final Direction back;
  private final int stableIndex;
  private final String key;

  private MachineOrientation(Direction front, int roll, Direction up) {
    this.front = front;
    this.roll = roll;
    this.up = up;
    right = cross(front, up);
    back = front.getOpposite();
    stableIndex = frontIndex(front) * 4 + roll;
    key = front.getSerializedName() + "_" + roll;
  }

  public static MachineOrientation of(Direction front, int roll) {
    Objects.requireNonNull(front, "front");
    if (roll < 0 || roll > 3) {
      throw new IllegalArgumentException("roll must be in [0, 3]");
    }
    return ORIENTATIONS[frontIndex(front)][roll];
  }

  public static MachineOrientation fromFrontAndUp(Direction front, Direction up) {
    Objects.requireNonNull(front, "front");
    Objects.requireNonNull(up, "up");
    if (front.getAxis() == up.getAxis()) {
      throw new IllegalArgumentException("front and up must be perpendicular");
    }
    for (MachineOrientation orientation : ORIENTATIONS[frontIndex(front)]) {
      if (orientation.up == up) {
        return orientation;
      }
    }
    throw new IllegalStateException("Missing cube rotation");
  }

  /**
   * Stable order: NORTH, EAST, SOUTH, WEST, UP, DOWN; four rolls for each front.
   */
  public static List<MachineOrientation> all() {
    return ALL;
  }

  public Direction front() {
    return front;
  }

  public int roll() {
    return roll;
  }

  public Direction up() {
    return up;
  }

  public Direction right() {
    return right;
  }

  public Direction back() {
    return back;
  }

  public String key() {
    return key;
  }

  /**
   * For internal masks only. Persist front and roll rather than an enum ordinal
   * or this index.
   */
  public int stableIndex() {
    return stableIndex;
  }

  public int maskBit() {
    return 1 << stableIndex;
  }

  public BlockPos toWorldOffset(BlockPos local) {
    Objects.requireNonNull(local, "local");
    int x = local.getX(), y = local.getY(), z = local.getZ();
    return new BlockPos(
        component(x, y, z, right.getStepX(), up.getStepX(), back.getStepX()),
        component(x, y, z, right.getStepY(), up.getStepY(), back.getStepY()),
        component(x, y, z, right.getStepZ(), up.getStepZ(), back.getStepZ()));
  }

  public BlockPos toLocalOffset(BlockPos world) {
    Objects.requireNonNull(world, "world");
    int x = world.getX(), y = world.getY(), z = world.getZ();
    return new BlockPos(
        component(x, y, z, right.getStepX(), right.getStepY(), right.getStepZ()),
        component(x, y, z, up.getStepX(), up.getStepY(), up.getStepZ()),
        component(x, y, z, back.getStepX(), back.getStepY(), back.getStepZ()));
  }

  public Direction toWorldDirection(Direction local) {
    return switch (Objects.requireNonNull(local, "local")) {
      case NORTH -> front;
      case SOUTH -> back;
      case EAST -> right;
      case WEST -> right.getOpposite();
      case UP -> up;
      case DOWN -> up.getOpposite();
    };
  }

  public Direction toLocalDirection(Direction world) {
    Objects.requireNonNull(world, "world");
    if (world == front) {
      return Direction.NORTH;
    }
    if (world == back) {
      return Direction.SOUTH;
    }
    if (world == right) {
      return Direction.EAST;
    }
    if (world == right.getOpposite()) {
      return Direction.WEST;
    }
    return world == up ? Direction.UP : Direction.DOWN;
  }

  public Direction.Axis toWorldAxis(Direction.Axis local) {
    return switch (Objects.requireNonNull(local, "local")) {
      case X -> right.getAxis();
      case Y -> up.getAxis();
      case Z -> back.getAxis();
    };
  }

  public Direction.Axis toLocalAxis(Direction.Axis world) {
    Direction positive = switch (Objects.requireNonNull(world, "world")) {
      case X -> Direction.EAST;
      case Y -> Direction.UP;
      case Z -> Direction.SOUTH;
    };
    return toLocalDirection(positive).getAxis();
  }

  /**
   * Positive turns are clockwise when looking at the controller from outside its
   * front.
   */
  public MachineOrientation rollBy(int quarterTurns) {
    return of(front, (roll + Math.floorMod(quarterTurns, 4)) & 3);
  }

  /**
   * Keep the previous world-up direction if possible; otherwise use the new
   * front's roll zero.
   */
  public MachineOrientation withFront(Direction newFront) {
    Objects.requireNonNull(newFront, "newFront");
    return newFront.getAxis() == up.getAxis() ? of(newFront, 0) : fromFrontAndUp(newFront, up);
  }

  /** a.compose(b) applies b first, then a. */
  public MachineOrientation compose(MachineOrientation before) {
    Objects.requireNonNull(before, "before");
    return fromFrontAndUp(toWorldDirection(before.front), toWorldDirection(before.up));
  }

  public MachineOrientation inverse() {
    return fromFrontAndUp(toLocalDirection(Direction.NORTH), toLocalDirection(Direction.UP));
  }

  @Override
  public String toString() {
    return key;
  }

  private static MachineOrientation[][] createOrientations() {
    MachineOrientation[][] result = new MachineOrientation[6][4];
    for (Direction front : FRONT_ORDER) {
      Direction up = switch (front) {
        case UP -> Direction.SOUTH;
        case DOWN -> Direction.NORTH;
        default -> Direction.UP;
      };
      for (int roll = 0; roll < 4; roll++) {
        MachineOrientation orientation = new MachineOrientation(front, roll, up);
        result[frontIndex(front)][roll] = orientation;
        up = orientation.right.getOpposite();
      }
    }
    return result;
  }

  private static List<MachineOrientation> createAll() {
    List<MachineOrientation> result = new ArrayList<>(24);
    for (MachineOrientation[] rotations : ORIENTATIONS) {
      result.addAll(List.of(rotations));
    }
    return List.copyOf(result);
  }

  private static int frontIndex(Direction front) {
    return switch (front) {
      case NORTH -> 0;
      case EAST -> 1;
      case SOUTH -> 2;
      case WEST -> 3;
      case UP -> 4;
      case DOWN -> 5;
    };
  }

  private static Direction cross(Direction first, Direction second) {
    int x = first.getStepY() * second.getStepZ() - first.getStepZ() * second.getStepY();
    int y = first.getStepZ() * second.getStepX() - first.getStepX() * second.getStepZ();
    int z = first.getStepX() * second.getStepY() - first.getStepY() * second.getStepX();
    for (Direction direction : FRONT_ORDER) {
      if (direction.getStepX() == x && direction.getStepY() == y && direction.getStepZ() == z) {
        return direction;
      }
    }
    throw new IllegalArgumentException("Expected perpendicular unit directions");
  }

  /** A signed permutation has exactly one nonzero factor per output component. */
  private static int component(int x, int y, int z, int xFactor, int yFactor, int zFactor) {
    if (xFactor != 0) {
      return xFactor > 0 ? x : Math.negateExact(x);
    }
    if (yFactor != 0) {
      return yFactor > 0 ? y : Math.negateExact(y);
    }
    return zFactor > 0 ? z : Math.negateExact(z);
  }
}
