package com.lw.modularmachine.machine.structure;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.ApiStatus;

/** Internal export-time collision proof over every permitted dynamic length. */
@ApiStatus.Internal
public final class DynamicLayoutValidator {
  private static final long MAX_WORK = 8_388_608L;
  private static final int MAX_POSITIONS = 1_048_576;

  private record Position(int x, int y, int z) {
    Position(BlockPos position) {
      this(position.getX(), position.getY(), position.getZ());
    }
  }

  private enum Kind {
    FIXED,
    START,
    BODY,
    END
  }

  private record Claim(String segment, Kind kind, int index, String part, Claim next) {
  }

  private final Map<Position, Claim> occupied = new HashMap<>();
  private long work;

  private DynamicLayoutValidator() {
  }

  public static void validate(MachineDefinition definition) {
    var validator = new DynamicLayoutValidator();
    var source = definition.sourceDefinition();
    validator.add(BlockPos.ZERO, "", Kind.FIXED, 0, "controller");
    for (var part : source.parts()) {
      validator.add(part.offset(), "", Kind.FIXED, 0, part.id());
    }
    for (var segment : source.dynamicPatterns()) {
      for (var part : segment.start()) {
        validator.add(
            DynamicPatternDefinition.translated(part.offset(), segment.startOffset(), segment.step(), 0),
            segment.id(),
            Kind.START,
            0,
            part.id());
      }
      for (int index = 0; index < segment.maximum(); index++) {
        for (var part : segment.body()) {
          validator.add(
              DynamicPatternDefinition.translated(part.offset(), segment.startOffset(), segment.step(), index),
              segment.id(),
              Kind.BODY,
              index,
              part.id());
        }
      }
      // Ends at different lengths never coexist; an end at n coexists only with body
      // indices < n.
      for (int index = segment.minimum(); index <= segment.maximum(); index++) {
        for (var part : segment.end()) {
          validator.add(
              DynamicPatternDefinition.translated(part.offset(), segment.startOffset(), segment.step(), index),
              segment.id(),
              Kind.END,
              index,
              part.id());
        }
      }
    }
  }

  private void add(BlockPos position, String segment, Kind kind, int index, String part) {
    consume();
    var key = new Position(position);
    var previous = occupied.get(key);
    for (var claim = previous; claim != null; claim = claim.next()) {
      consume();
      if (coexists(claim, segment, kind, index)) {
        throw new IllegalArgumentException(
            "Dynamic layout collision at " + position.toShortString() + ": " + describe(
                claim.segment(),
                claim.kind(),
                claim.index(),
                claim.part()) + " / " + describe(segment, kind, index, part));
      }
    }
    if (previous == null && occupied.size() >= MAX_POSITIONS) {
      throw new IllegalArgumentException("Dynamic collision validation exceeds its 1048576-position budget");
    }
    occupied.put(key, new Claim(segment, kind, index, part, previous));
  }

  private void consume() {
    if (++work > MAX_WORK) {
      throw new IllegalArgumentException("Dynamic collision validation exceeds its work budget");
    }
    if ((work & 4095) == 0 && Thread.currentThread().isInterrupted()) {
      throw new CancellationException();
    }
  }

  private static boolean coexists(Claim before, String segment, Kind kind, int index) {
    if (!before.segment().equals(segment) || before.kind() == Kind.FIXED || kind == Kind.FIXED) {
      return true;
    }
    if (before.kind() == Kind.END && kind == Kind.END) {
      return before.index() == index;
    }
    if (before.kind() == Kind.END && kind == Kind.BODY) {
      return index < before.index();
    }
    if (before.kind() == Kind.BODY && kind == Kind.END) {
      return before.index() < index;
    }
    return true;
  }

  private static String describe(String segment, Kind kind, int index, String part) {
    return segment.isEmpty() ? part : segment + "/" + kind + "/" + index + "/" + part;
  }
}
