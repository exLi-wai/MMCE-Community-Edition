package com.lw.modularmachine.machine.structure;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
    FIXED(""),
    START("start"),
    BODY("body"),
    END("end");

    final String group;

    Kind(String group) {
      this.group = group;
    }
  }

  private record Claim(String segment, Kind kind, int index, String part, Claim next) {
  }

  private record Part(String id, BlockPos offset) {
    Part(MachineDefinition.Part source) {
      this(source.id(), source.offset());
    }
  }

  private final Map<Position, Claim> occupied = new HashMap<>();
  private final HashSet<String> fixedIds = new HashSet<>();
  private long work;

  private DynamicLayoutValidator() {
  }

  public static void validate(MachineDefinition definition) {
    var validator = new DynamicLayoutValidator();
    var source = definition.sourceDefinition();
    validator.add(BlockPos.ZERO, "", Kind.FIXED, 0, "controller");
    for (var part : source.parts()) {
      validator.fixed(part.offset(), part.id());
    }
    for (var segment : source.dynamicPatterns()) {
      validator.segment(
          segment.id(),
          segment.minimum(),
          segment.maximum(),
          segment.startOffset(),
          segment.step(),
          segment.start().stream().map(Part::new).toList(),
          segment.body().stream().map(Part::new).toList(),
          segment.end().stream().map(Part::new).toList());
    }
  }

  /**
   * Proves the complete client-edited layout without looking up any world or
   * registry objects.
   */
  public static void validate(JsonObject root) {
    var validator = new DynamicLayoutValidator();
    validator.add(BlockPos.ZERO, "", Kind.FIXED, 0, "controller");
    for (var part : parts(root.getAsJsonArray("parts"))) {
      validator.fixed(part.offset(), part.id());
    }
    for (var segment : DynamicPatternDefinition.declarations(root, "$")) {
      validator.segment(
          segment.id(),
          segment.minimum(),
          segment.maximum(),
          segment.offset(),
          segment.step(),
          parts(segment.start()),
          parts(segment.body()),
          parts(segment.end()));
    }
  }

  private static List<Part> parts(JsonArray declarations) {
    var result = new ArrayList<Part>(declarations.size());
    var ids = new HashSet<String>();
    for (var declaration : declarations) {
      var part = declaration.getAsJsonObject();
      var offset = MachinePartParser.offset(part, "part");
      String id = MachinePartParser.partId(part, offset, "part");
      if (!ids.add(id)) {
        throw new IllegalArgumentException("Duplicate part ID: " + id);
      }
      result.add(new Part(id, offset));
    }
    return result;
  }

  private void segment(String id, int minimum, int maximum, BlockPos start, BlockPos step, List<Part> head, List<Part> body, List<Part> tail) {
    if (minimum < 0
        || minimum > maximum
        || step.equals(BlockPos.ZERO)
        || body.isEmpty()
        || (long) head.size() + (long) maximum * body.size() + tail
            .size() > DynamicPatternDefinition.MAX_EXPANDED_PARTS) {
      throw new IllegalArgumentException("Invalid dynamic segment geometry: " + id);
    }
    for (var part : body) {
      DynamicPatternDefinition.translated(part.offset(), start, step, maximum);
    }
    group(id, Kind.START, start, step, head, 0, 0);
    group(id, Kind.BODY, start, step, body, 0, maximum - 1);
    // Ends at different lengths never coexist; an end at n coexists only with body
    // indices < n.
    group(id, Kind.END, start, step, tail, minimum, maximum);
  }

  private void fixed(BlockPos offset, String id) {
    fixedIds.add(id);
    add(offset, "", Kind.FIXED, 0, id);
  }

  private void group(String segment, Kind kind, BlockPos start, BlockPos step, List<Part> parts, int first, int last) {
    if (parts.isEmpty()) {
      return;
    }
    // Other segments, groups and repetitions have distinct generated prefixes.
    var ids = new HashSet<String>();
    for (int index = first; index <= last; index++) {
      ids.clear();
      for (var part : parts) {
        String id = MachineDefinition.expandedPartId(segment, kind.group, index, part.id());
        if (fixedIds.contains(id) || !ids.add(id)) {
          throw new IllegalArgumentException("Duplicate expanded part ID: " + id);
        }
        add(DynamicPatternDefinition.translated(part.offset(), start, step, index), segment, kind, index, part.id());
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
