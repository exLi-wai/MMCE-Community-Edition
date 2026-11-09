package com.lw.modularmachine.machine.structure;

import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.orientation.OrientationCompilation;
import com.lw.modularmachine.machine.orientation.PropertySpace;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal, budgeted length discovery. A match is only a selection hint: the
 * expanded layout still needs the ordinary structure and modifier proofs.
 */
@ApiStatus.Internal
public final class DynamicStructureMatcher {
  private enum Phase {
    START,
    BODY,
    END,
    LIMIT_END,
    PREVIOUS_END
  }

  @FunctionalInterface
  interface PartCheck {
    CheckResult check(BlockPos position, MachineDefinition.Part part, MachineDefinition.Rule rule, Rotation inverse);
  }

  @FunctionalInterface
  interface Watch {
    void accept(int segment, BlockPos position, boolean contents);
  }

  private record Probe(MachineDefinition.Part part, MachineDefinition.Rule rule, Rotation inverse) {
  }

  private record Segment(DynamicPatternDefinition definition, List<Probe> start, List<Probe> body, List<Probe> end) {
  }

  public record Failure(BlockPos position, String partId, MachineDefinition.Rule expected, VerificationFailure reason) {
  }

  private final BlockPos origin;
  private final MachineOrientation orientation;
  private final List<Segment> segments;
  private final Map<String, Integer> lengths = new LinkedHashMap<>();
  private int segmentIndex, count, cursor;
  private long pendingSegments;
  private Phase phase = Phase.START;
  private Failure failure;

  /** Pure preparation; safe on the structure worker with detached definitions. */
  public DynamicStructureMatcher(MachineDefinition definition, BlockPos origin, MachineOrientation orientation) {
    this.origin = origin.immutable();
    this.orientation = orientation;
    var prepared = new ArrayList<Segment>();
    for (var segment : definition.dynamicPatterns()) {
      prepared.add(
          new Segment(
              segment,
              probes(segment.start(), segment.startCompilation(), orientation),
              probes(segment.body(), segment.bodyCompilation(), orientation),
              probes(segment.end(), segment.endCompilation(), orientation)));
    }
    segments = List.copyOf(prepared);
    pendingSegments = segments.isEmpty() ? 0 : -1L >>> (64 - segments.size());
  }

  private static List<Probe> probes(List<MachineDefinition.Part> parts, OrientationCompilation compilation, MachineOrientation orientation) {
    if (parts.isEmpty()) {
      return List.of();
    }
    var rules = compilation.rulesFor(orientation);
    var result = new ArrayList<Probe>(parts.size());
    for (int index = 0; index < parts.size(); index++) {
      var part = parts.get(index);
      Rotation inverse = Rotation.NONE;
      if (part.propertiesSpace() == PropertySpace.LEGACY
          && part.rule().alternatives().stream().anyMatch(alternative -> !alternative.properties().isEmpty())) {
        inverse = switch (orientation.front()) {
          case NORTH -> Rotation.NONE;
          case EAST -> Rotation.COUNTERCLOCKWISE_90;
          case SOUTH -> Rotation.CLOCKWISE_180;
          case WEST -> Rotation.CLOCKWISE_90;
          default -> throw new IllegalArgumentException("Legacy dynamic properties require horizontal orientation");
        };
      }
      result.add(new Probe(part, rules.get(index), inverse));
    }
    return List.copyOf(result);
  }

  public int advance(ServerLevel level, int budget, BiConsumer<BlockPos, Boolean> watch) {
    return advance(level, budget, (segment, position, contents) -> watch.accept(position, contents));
  }

  int advance(ServerLevel level, int budget, Watch watch) {
    ServerThreadGuard.require(level, "Dynamic structure length discovery");
    return advance(
        budget,
        watch,
        (position, part, rule, inverse) -> CompiledStructure.checkPart(level, position, part, rule, inverse));
  }

  /** Detached predicate driver; live reads remain in the guarded server entry. */
  int advance(int budget, BiConsumer<BlockPos, Boolean> watch, PartCheck check) {
    return advance(budget, (segment, position, contents) -> watch.accept(position, contents), check);
  }

  int advance(int budget, Watch watch, PartCheck check) {
    int used = 0;
    while (used < budget && !complete()) {
      var segment = segments.get(segmentIndex);
      var probes = switch (phase) {
        case START -> segment.start();
        case BODY -> segment.body();
        case END, LIMIT_END, PREVIOUS_END -> segment.end();
      };
      if (cursor == probes.size()) {
        used++;
        matchedPhase(segment);
        continue;
      }
      var probe = probes.get(cursor);
      int repetition = phase == Phase.START ? 0 : phase == Phase.PREVIOUS_END ? count - 1 : count;
      var local = translated(segment.definition(), probe.part().offset(), repetition);
      var offset = orientation.toWorldOffset(local);
      var position = new BlockPos(
          Math.addExact(origin.getX(), offset.getX()),
          Math.addExact(origin.getY(), offset.getY()),
          Math.addExact(origin.getZ(), offset.getZ()));
      // Subscribe before reading, including the first nonmatching boundary.
      CheckResult result;
      if (position.equals(origin)) {
        // The owning controller cannot be a repeated component. Its identity
        // and orientation use the owner's gate, not visual-state subscriptions.
        result = CheckResult.MISMATCH;
      } else {
        watch.accept(segmentIndex, position, probe.part().field() != null);
        result = check.check(position, probe.part(), probe.rule(), probe.inverse());
      }
      used++;
      if (result == CheckResult.MATCH) {
        cursor++;
      } else if (result == CheckResult.UNAVAILABLE || result == CheckResult.UNSUPPORTED) {
        failure = new Failure(
            position,
            probe.part().id(),
            probe.rule(),
            result == CheckResult.UNAVAILABLE ? VerificationFailure.CHUNK_UNAVAILABLE : VerificationFailure.UNSUPPORTED_SCHEMA);
      } else if (phase == Phase.BODY) {
        phase = Phase.END;
        cursor = 0;
        if (segment.end().isEmpty() && count < segment.definition().minimum()) {
          failure = new Failure(position, probe.part().id(), probe.rule(), VerificationFailure.MISMATCH);
        }
      } else if (phase == Phase.END && count > segment.definition().minimum()) {
        phase = Phase.PREVIOUS_END;
        cursor = 0;
      } else {
        failure = new Failure(position, probe.part().id(), probe.rule(), VerificationFailure.MISMATCH);
      }
    }
    return used;
  }

  private void matchedPhase(Segment segment) {
    cursor = 0;
    if (phase == Phase.START) {
      phase = Phase.BODY;
    } else if (phase == Phase.BODY) {
      if (count == segment.definition().maximum()) {
        if (!segment.end().isEmpty()) {
          // A tail can satisfy the body too. At the limit it must match here;
          // neither another body nor the normal one-body fallback is allowed.
          phase = Phase.LIMIT_END;
        } else {
          var probe = segment.body().get(0);
          var offset = orientation.toWorldOffset(translated(segment.definition(), probe.part().offset(), count));
          failure = new Failure(origin.offset(offset), probe.part().id(), probe.rule(), VerificationFailure.MISMATCH);
        }
      } else {
        count++;
      }
    } else {
      int length = phase == Phase.PREVIOUS_END ? count - 1 : count;
      if (length < segment.definition().minimum()) {
        var probe = segment.end().isEmpty() ? segment.body().get(0) : segment.end().get(0);
        failure = new Failure(
            origin.offset(orientation.toWorldOffset(translated(segment.definition(), probe.part().offset(), length))),
            probe.part().id(),
            probe.rule(),
            VerificationFailure.MISMATCH);
        return;
      }
      lengths.put(segment.definition().id(), length);
      pendingSegments &= ~(1L << segmentIndex);
      segmentIndex = Long.numberOfTrailingZeros(pendingSegments);
      count = 0;
      phase = Phase.START;
    }
  }

  /**
   * Main-thread invalidation keeps completed, independent segments and their
   * rules.
   */
  long invalidate(long affected) {
    long all = segments.isEmpty() ? 0 : -1L >>> (64 - segments.size());
    affected &= all;
    if (affected == 0) {
      return 0;
    }
    if (failure != null) {
      affected |= 1L << segmentIndex;
    }
    boolean restart = pendingSegments == 0 || failure != null || (affected & (1L << segmentIndex)) != 0;
    for (long remaining = affected; remaining != 0; remaining &= remaining - 1) {
      lengths.remove(segments.get(Long.numberOfTrailingZeros(remaining)).definition().id());
    }
    pendingSegments |= affected;
    if (restart) {
      segmentIndex = Long.numberOfTrailingZeros(pendingSegments);
      count = 0;
      cursor = 0;
      phase = Phase.START;
      failure = null;
    }
    return affected;
  }

  private static BlockPos translated(DynamicPatternDefinition segment, BlockPos part, int repetition) {
    return DynamicPatternDefinition.translated(part, segment.startOffset(), segment.step(), repetition);
  }

  public boolean complete() {
    return failure != null || pendingSegments == 0;
  }

  public boolean matched() {
    return complete() && failure == null;
  }

  public Failure failure() {
    return failure;
  }

  public Map<String, Integer> lengths() {
    if (!matched()) {
      throw new IllegalStateException("Dynamic structure lengths are not confirmed");
    }
    return Map.copyOf(lengths);
  }
}
