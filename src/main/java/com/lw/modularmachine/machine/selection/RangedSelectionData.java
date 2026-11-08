package com.lw.modularmachine.machine.selection;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/** Internal detached messages for the existing ranged tool's editor. */
@ApiStatus.Internal
public final class RangedSelectionData {
  public static final int MAX_SEGMENTS = 64;
  public static final int MAX_CAPTURE = 262_144;
  public static final UUID NO_SESSION = new UUID(0, 0);

  private RangedSelectionData() {
  }

  public enum PickTarget {
    NONE,
    MAIN,
    BODY,
    START,
    END
  }

  public enum Action {
    OPEN,
    BEGIN_PICK,
    CANCEL_PICK,
    CLEAR_BOX,
    SET_MODE,
    LIST_FILES,
    SELECT_FILE,
    ADD_SEGMENT,
    SELECT_SEGMENT,
    REMOVE_SEGMENT,
    UPDATE,
    CLEAR,
    PREVIEW
  }

  public record Box(BlockPos first, BlockPos second) {
    public Box {
      first = Objects.requireNonNull(first).immutable();
      second = Objects.requireNonNull(second).immutable();
    }
    public long volume() {
      long x = Math.abs((long) first.getX() - second.getX()) + 1;
      long y = Math.abs((long) first.getY() - second.getY()) + 1;
      long z = Math.abs((long) first.getZ() - second.getZ()) + 1;
      return Math.multiplyExact(Math.multiplyExact(x, y), z);
    }
  }

  public record Settings(
      String id,
      int minimum,
      int maximum,
      Direction direction,
      int gap,
      boolean customStep,
      BlockPos step,
      boolean customOrigin,
      BlockPos origin,
      int sampleLength,
      String selector,
      String interfaceType) {
    public Settings {
      Objects.requireNonNull(id);
      Objects.requireNonNull(direction);
      step = Objects.requireNonNull(step).immutable();
      origin = Objects.requireNonNull(origin).immutable();
      Objects.requireNonNull(selector);
      Objects.requireNonNull(interfaceType);
      if (!id.isEmpty() && !id.matches("[a-zA-Z0-9_.-]{1,64}")
          || minimum < 0
          || maximum < minimum
          || maximum > 4_194_304
          || gap < 0
          || gap > 1_000_000
          || sampleLength < 0
          || sampleLength > 4_194_304
          || customStep && step.equals(BlockPos.ZERO)
          || selector.length() > 64
          || interfaceType.length() > 64) {
        throw new IllegalArgumentException("Invalid dynamic selection parameters");
      }
    }
    public static Settings defaults() {
      return new Settings("", 1, 8, Direction.SOUTH, 0, false, new BlockPos(0, 0, 1), false, BlockPos.ZERO, 1, "", "");
    }
  }

  public record Segment(Settings settings, Box body, Box start, Box end) {
    public Segment {
      Objects.requireNonNull(settings);
    }
  }

  public record FileOption(String token, ResourceLocation machine, String path, String label) {
    public FileOption {
      Objects.requireNonNull(token);
      Objects.requireNonNull(machine);
      Objects.requireNonNull(path);
      Objects.requireNonNull(label);
    }
  }

  /** index selects a segment/page, value is a file token, filter, or filename. */
  public record Request(
      UUID session,
      long revision,
      Action action,
      PickTarget target,
      int index,
      String value,
      Settings settings,
      boolean deduplicate,
      boolean includeAir) {
    public Request {
      Objects.requireNonNull(session);
      Objects.requireNonNull(action);
      Objects.requireNonNull(target);
      Objects.requireNonNull(value);
      Objects.requireNonNull(settings);
      if (revision < 0 || value.length() > 256 || index < -1 || index > 4_194_304) {
        throw new IllegalArgumentException("Invalid ranged selection action");
      }
    }
  }

  public record Snapshot(
      UUID session,
      long revision,
      ResourceLocation dimension,
      boolean open,
      boolean close,
      boolean append,
      String fileToken,
      String fileLabel,
      String fileName,
      boolean deduplicate,
      boolean includeAir,
      Box main,
      List<Segment> segments,
      int selected,
      PickTarget picking,
      BlockPos first,
      Component status,
      boolean busy,
      List<FileOption> files,
      int filePage,
      int filePages,
      boolean hasResult) {
    public Snapshot {
      Objects.requireNonNull(session);
      Objects.requireNonNull(dimension);
      Objects.requireNonNull(fileToken);
      Objects.requireNonNull(fileLabel);
      Objects.requireNonNull(fileName);
      Objects.requireNonNull(picking);
      status = Objects.requireNonNull(status).copy();
      first = first == null ? null : first.immutable();
      segments = List.copyOf(segments);
      files = List.copyOf(files);
      if (revision < 0
          || segments.size() > MAX_SEGMENTS
          || files.size() > 32
          || filePage < 0
          || filePages < 1
          || selected < 0
          || selected >= Math.max(1, segments.size())) {
        throw new IllegalArgumentException("Invalid ranged selection snapshot");
      }
    }
  }
}
