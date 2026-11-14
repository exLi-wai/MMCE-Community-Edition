package com.lw.modularmachine.machine.selection;

import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Box;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Settings;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.ApiStatus;

/** Internal pure geometry shared by selected-world previews and export. */
@ApiStatus.Internal
public final class SelectionGeometry {
  public record Bounds(BlockPos min, BlockPos max) {
    public Bounds {
      min = Objects.requireNonNull(min).immutable();
      max = Objects.requireNonNull(max).immutable();
      if (min.getX() > max.getX() || min.getY() > max.getY() || min.getZ() > max.getZ()) {
        throw new IllegalArgumentException("Invalid selection bounds");
      }
    }
  }

  private SelectionGeometry() {
  }

  /** Converts all eight corners before finding local-space bounds. */
  public static Bounds bounds(Box box, BlockPos controller, MachineOrientation orientation) {
    BlockPos min = null;
    BlockPos max = null;
    for (int x : new int[]{box.first().getX(), box.second().getX()}) {
      for (int y : new int[]{box.first().getY(), box.second().getY()}) {
        for (int z : new int[]{box.first().getZ(), box.second().getZ()}) {
          var corner = local(new BlockPos(x, y, z), controller, orientation);
          min = min == null ? corner : new BlockPos(
              Math.min(min.getX(), corner.getX()),
              Math.min(min.getY(), corner.getY()),
              Math.min(min.getZ(), corner.getZ()));
          max = max == null ? corner : new BlockPos(
              Math.max(max.getX(), corner.getX()),
              Math.max(max.getY(), corner.getY()),
              Math.max(max.getZ(), corner.getZ()));
        }
      }
    }

    return new Bounds(min, max);
  }

  public static BlockPos step(Settings settings, Bounds bounds) {
    if (settings.customStep()) {
      return settings.step();
    }
    Direction direction = settings.direction();
    long length = switch (direction.getAxis()) {
      case X -> (long) bounds.max().getX() - bounds.min().getX() + 1;
      case Y -> (long) bounds.max().getY() - bounds.min().getY() + 1;
      case Z -> (long) bounds.max().getZ() - bounds.min().getZ() + 1;
    };
    length = Math.addExact(length, settings.gap());

    return new BlockPos(
        Math.toIntExact(length * direction.getStepX()),
        Math.toIntExact(length * direction.getStepY()),
        Math.toIntExact(length * direction.getStepZ()));
  }

  public static BlockPos local(BlockPos world, BlockPos controller, MachineOrientation orientation) {
    long x = (long) world.getX() - controller.getX();
    long y = (long) world.getY() - controller.getY();
    long z = (long) world.getZ() - controller.getZ();

    return new BlockPos(
        dot(x, y, z, orientation.right()),
        dot(x, y, z, orientation.up()),
        dot(x, y, z, orientation.back()));
  }

  private static int dot(long x, long y, long z, Direction axis) {
    return Math.toIntExact(x * axis.getStepX() + y * axis.getStepY() + z * axis.getStepZ());
  }

  /**
   * Removes the template origin and, for a sampled tail, its body repetitions.
   */
  public static BlockPos relative(BlockPos value, BlockPos base, BlockPos step, int repetition) {
    return new BlockPos(
        Math.toIntExact((long) value.getX() - base.getX() - (long) step.getX() * repetition),
        Math.toIntExact((long) value.getY() - base.getY() - (long) step.getY() * repetition),
        Math.toIntExact((long) value.getZ() - base.getZ() - (long) step.getZ() * repetition));
  }
}
