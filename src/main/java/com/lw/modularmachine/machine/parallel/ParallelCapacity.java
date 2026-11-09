package com.lw.modularmachine.machine.parallel;

import com.lw.modularmachine.block.ParallelControllerBlock;
import com.lw.modularmachine.blockentity.ParallelControllerBlockEntity;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Indexed, immutable admission proof. Paid runs never consult component
 * settings again.
 */
public final class ParallelCapacity {
  private record Source(
      BlockPos position,
      BlockState state,
      ParallelControllerBlockEntity identity,
      long revision,
      int value,
      int maximum) {
    boolean current(ServerLevel level) {
      return level.hasChunkAt(position)
          && level.getBlockState(position) == state
          && (identity == null
              || level.getBlockEntity(position) == identity
                  && !identity.isRemoved()
                  && identity.schemaSupported()
                  && identity.revision() == revision
                  && identity.parallelism() == value
                  && identity.maximum() == maximum);
    }
  }

  public static final class Snapshot {
    private final ServerLevel level;
    private final List<Source> sources;
    private final int limit;

    private Snapshot(ServerLevel level, List<Source> sources, int limit) {
      this.level = level;
      this.sources = List.copyOf(sources);
      this.limit = limit;
    }

    public int limit() {
      return limit;
    }

    public boolean current() {
      ServerThreadGuard.require(level, "Parallel capacity proof");

      return sources.stream().allMatch(source -> source.current(level));
    }
  }

  private ParallelCapacity() {
  }

  public static Snapshot capture(StructureService.Registration registration, ServerLevel level) {
    ServerThreadGuard.require(level, "Parallel capacity capture");
    if (!registration.pattern.definition.parallelizable()) {
      return new Snapshot(level, List.of(), 1);
    }
    long total = registration.pattern.definition.internalParallelism();
    int maximum = registration.pattern.definition.maxParallelism();
    var sources = new ArrayList<Source>();
    for (int index : registration.parallelParts()) {
      var position = registration.pattern.position(index);
      if (!level.hasChunkAt(position)) {
        throw new IllegalStateException("Unloaded parallel controller position");
      }
      var state = level.getBlockState(position);
      ParallelControllerBlockEntity parallel = null;
      if (state.getBlock() instanceof ParallelControllerBlock) {
        if (!(level.getBlockEntity(position) instanceof ParallelControllerBlockEntity found)
            || !found.schemaSupported()) {
          throw new IllegalStateException("Unavailable parallel controller");
        }
        parallel = found;
        total = Math.min(maximum, total + parallel.parallelism());
      }
      sources.add(
          new Source(
              position,
              state,
              parallel,
              parallel == null ? 0 : parallel.revision(),
              parallel == null ? 0 : parallel.parallelism(),
              parallel == null ? 0 : parallel.maximum()));
    }

    return new Snapshot(level, sources, (int) Math.max(1, Math.min(maximum, total)));
  }
}
