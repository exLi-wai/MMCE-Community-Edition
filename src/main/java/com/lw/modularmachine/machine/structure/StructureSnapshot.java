package com.lw.modularmachine.machine.structure;

import com.lw.modularmachine.block.ParallelControllerBlock;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.blockentity.ParallelControllerBlockEntity;
import com.lw.modularmachine.blockentity.SmartInterfaceBlockEntity;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Server-owned capture builder; only its detached immutable result reaches
 * workers.
 */
public final class StructureSnapshot {
  private StructureSnapshot() {
  }

  public record Result(VerificationFailure failure, int failedIndex, StructureModifierTracker.Reference modifiers) {
    public boolean matched() {
      return failure == VerificationFailure.NONE;
    }
  }

  public static final class Capture {
    private final CompiledStructure pattern;
    private final int[] checks;
    private final int[] captureIndices;
    private final BlockState[] states;
    private final BlockState[] baseStates;
    private final CheckResult[] details;
    private final Map<Long, LevelChunk> chunks = new HashMap<>();
    private int cursor;

    public Capture(CompiledStructure pattern, int[] checks) {
      this.pattern = pattern;
      this.checks = checks.clone();
      var required = new BitSet();
      for (int index : checks) {
        required.set(index);
      }
      for (var condition : pattern.modifierPlan().conditions) {
        required.set(condition.part());
      }
      captureIndices = required.stream().toArray();
      states = new BlockState[pattern.definition.parts().size()];
      baseStates = new BlockState[states.length];
      details = new CheckResult[states.length];
    }

    public boolean complete() {
      return cursor == captureIndices.length;
    }

    public int advance(ServerLevel level, int batch) {
      ServerThreadGuard.require(level, "Structure snapshot captured");
      // Cached chunks are scoped to this main-thread slice; unloaded chunks cannot
      // remain reachable through a capture spanning multiple server ticks.
      chunks.clear();
      int used = 0;
      while (used < batch && !complete()) {
        int index = captureIndices[cursor++];
        var pos = pattern.position(index);
        long key = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        LevelChunk chunk = null;
        if (!level.isOutsideBuildHeight(pos)) {
          if (chunks.containsKey(key)) {
            chunk = chunks.get(key);
          } else {
            chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            chunks.put(key, chunk);
          }
        }
        if (chunk != null) {
          var state = chunk.getBlockState(pos);
          states[index] = state;
          baseStates[index] = pattern.snapshotState(index, state);
          details[index] = captureDetails(chunk, pos, state, pattern.definition.parts().get(index));
        }
        used++;
      }
      chunks.clear();

      return used;
    }

    private static CheckResult captureDetails(LevelChunk chunk, BlockPos pos, BlockState state, MachineDefinition.Part part) {
      boolean needsEntity = part.rule().port() != null
          || part.field() != null
          || !part.interfaceType().isEmpty()
          || state.getBlock() instanceof ParallelControllerBlock;
      BlockEntity entity = needsEntity ? chunk.getBlockEntity(pos) : null;
      if (part.rule().port() != null) {
        if (!(entity instanceof MachinePortBlockEntity port)) {
          return CheckResult.UNAVAILABLE;
        }
        if (!port.schemaSupported()) {
          return CheckResult.UNSUPPORTED;
        }
      }
      if (part.field() != null && (!(entity instanceof MachinePortBlockEntity port) || !part.field().matches(port))) {
        return CheckResult.MISMATCH;
      }
      if (!part.interfaceType().isEmpty()) {
        if (!(entity instanceof SmartInterfaceBlockEntity smart)) {
          return CheckResult.UNAVAILABLE;
        }
        if (!smart.schemaSupported()) {
          return CheckResult.UNSUPPORTED;
        }
      }
      if (state.getBlock() instanceof ParallelControllerBlock) {
        if (!(entity instanceof ParallelControllerBlockEntity parallel)) {
          return CheckResult.UNAVAILABLE;
        }
        if (!parallel.schemaSupported()) {
          return CheckResult.UNSUPPORTED;
        }
      }

      return CheckResult.MATCH;
    }

    public Detached finish() {
      if (!complete()) {
        throw new IllegalStateException("Structure snapshot is incomplete");
      }
      return new Detached(pattern, checks, states, baseStates, details);
    }
  }

  /** Owns completed arrays. The capture cannot modify them after finish. */
  public static final class Detached {
    private final CompiledStructure pattern;
    private final int[] checks;
    private final BlockState[] states;
    private final BlockState[] baseStates;
    private final CheckResult[] details;

    private Detached(CompiledStructure pattern, int[] checks, BlockState[] states, BlockState[] baseStates, CheckResult[] details) {
      this.pattern = pattern;
      this.checks = checks;
      this.states = states;
      this.baseStates = baseStates;
      this.details = details;
    }

    public Result match() {
      var plan = pattern.modifierPlan();
      var modifiers = plan.referenceSnapshot(index -> states[index]);
      if (modifiers.failure() != VerificationFailure.NONE) {
        return new Result(modifiers.failure(), modifiers.failedPart(), modifiers);
      }
      for (int index : checks) {
        if (Thread.currentThread().isInterrupted()) {
          throw new CancellationException();
        }
        var result = pattern.matchSnapshot(index, baseStates[index], details[index]);
        if (result == CheckResult.MISMATCH && plan.allows(index, modifiers)) {
          result = CheckResult.MATCH;
        }
        if (result != CheckResult.MATCH) {
          var failure = switch (result) {
            case UNAVAILABLE -> VerificationFailure.CHUNK_UNAVAILABLE;
            case UNSUPPORTED -> VerificationFailure.UNSUPPORTED_SCHEMA;
            default -> VerificationFailure.MISMATCH;
          };
          return new Result(failure, index, modifiers);
        }
      }

      return new Result(VerificationFailure.NONE, -1, modifiers);
    }
  }
}
