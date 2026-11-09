package com.lw.modularmachine.machine.appearance;

import com.lw.modularmachine.network.MachineVisibilityPayloads;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal main-thread visibility leases; never mutates blocks or inventories.
 */
@ApiStatus.Internal
public final class MachineComponentVisibility {
  private static final Map<ServerLevel, LevelState> LEVELS = new IdentityHashMap<>();
  private static long nextGeneration;

  private MachineComponentVisibility() {
  }

  public static Session create(ServerLevel level, BlockPos owner) {
    ServerThreadGuard.require(level, "Component visibility created");
    var state = LEVELS.computeIfAbsent(level, ignored -> new LevelState());

    nextGeneration = Math.incrementExact(nextGeneration);

    return new Session(level, state, owner.immutable(), nextGeneration);
  }

  @SubscribeEvent
  public static void chunkSent(ChunkWatchEvent.Sent event) {
    var state = LEVELS.get(event.getLevel());
    if (state == null) {
      return;
    }
    var sessions = state.chunks.get(event.getPos().toLong());
    if (sessions != null) {
      var key = new SyncKey(event.getPlayer().getUUID(), event.getPos().toLong());
      var previous = state.initialSyncs.remove(key);
      if (previous != null) {
        state.work.remove(previous);
      }
      var sync = new InitialSync(state, key, event.getPlayer(), event.getPos(), List.copyOf(sessions));
      state.initialSyncs.put(key, sync);
      state.work.addLast(sync);
    }
  }

  @SubscribeEvent
  public static void tick(LevelTickEvent.Post event) {
    if (!(event.getLevel() instanceof ServerLevel level)) {
      return;
    }
    var state = LEVELS.get(level);
    if (state == null) {
      return;
    }
    int budget = 1024;
    long deadline = System.nanoTime() + 1_000_000L;
    while (budget > 0 && System.nanoTime() < deadline && !state.work.isEmpty()) {
      Work work = state.work.removeFirst();
      budget -= work.advance(level, Math.min(budget, 256));
      if (!work.done()) {
        state.work.addLast(work);
      } else if (work instanceof InitialSync sync) {
        state.initialSyncs.remove(sync.key, sync);
      }
    }
  }

  @SubscribeEvent
  public static void levelUnloaded(LevelEvent.Unload event) {
    if (event.getLevel() instanceof ServerLevel level) {
      LEVELS.remove(level);
    }
  }

  @SubscribeEvent
  public static void stopped(ServerStoppedEvent event) {
    LEVELS.clear();
  }

  public static final class Session {
    private final ServerLevel level;
    private final LevelState state;
    private final BlockPos owner;
    private final long generation;
    private final Map<Long, List<BlockPos>> positions = new LinkedHashMap<>();
    private final Set<BlockPos> included = new LinkedHashSet<>();
    private final Map<Long, List<BlockPos>> pending = new LinkedHashMap<>();
    private long revision;
    private boolean active, removed;

    private Session(ServerLevel level, LevelState state, BlockPos owner, long generation) {
      this.level = level;
      this.state = state;
      this.owner = owner;
      this.generation = generation;
    }

    public void active(boolean value) {
      ServerThreadGuard.require(level, "Component visibility changed");
      if (removed || active == value) {
        return;
      }
      active = value;
      revision = Math.incrementExact(revision);
      MachineVisibilityPayloads.state(level, owner, generation, revision, active, false);
    }

    public void add(BlockPos position) {
      ServerThreadGuard.require(level, "Component visibility captured");
      if (removed || !included.add(position.immutable())) {
        return;
      }
      long chunk = ChunkPos.asLong(position.getX() >> 4, position.getZ() >> 4);
      positions.computeIfAbsent(chunk, ignored -> new ArrayList<>()).add(position.immutable());
      pending.computeIfAbsent(chunk, ignored -> new ArrayList<>()).add(position.immutable());
      state.chunks.computeIfAbsent(chunk, ignored -> new LinkedHashSet<>()).add(this);
    }

    public void flush() {
      ServerThreadGuard.require(level, "Component visibility published");
      if (removed) {
        return;
      }
      for (var entry : pending.entrySet()) {
        var chunk = new ChunkPos(entry.getKey());
        var values = entry.getValue();
        if (!level.hasChunk(chunk.x, chunk.z)) {
          continue;
        }
        for (int start = 0; start < values.size(); start += 256) {
          int end = Math.min(values.size(), start + 256);
          MachineVisibilityPayloads
              .members(level, null, owner, generation, revision, active, chunk, values.subList(start, end));
        }
      }
      pending.clear();
    }

    public void close() {
      ServerThreadGuard.require(level, "Component visibility retired");
      if (removed) {
        return;
      }
      active = false;
      removed = true;
      revision = Math.incrementExact(revision);
      MachineVisibilityPayloads.state(level, owner, generation, revision, false, true);
      pending.clear();
      state.work.addLast(new Cleanup(this));
    }
  }

  private interface Work {
    int advance(ServerLevel level, int budget);

    boolean done();
  }

  private static final class Cleanup implements Work {
    private final Session session;
    private final Iterator<Long> chunks;

    Cleanup(Session session) {
      this.session = session;
      chunks = session.positions.keySet().iterator();
    }

    @Override
    public int advance(ServerLevel level, int budget) {
      int used = 0;
      while (used < budget && chunks.hasNext()) {
        long chunk = chunks.next();
        var sessions = session.state.chunks.get(chunk);
        if (sessions != null) {
          sessions.remove(session);
          if (sessions.isEmpty()) {
            session.state.chunks.remove(chunk);
          }
        }
        chunks.remove();
        used++;
      }
      return Math.max(1, used);
    }

    @Override
    public boolean done() {
      return !chunks.hasNext();
    }
  }

  private static final class InitialSync implements Work {
    private final LevelState state;
    private final SyncKey key;
    private final ServerPlayer player;
    private final ChunkPos chunk;
    private final List<Session> sessions;
    private int sessionIndex, positionIndex;

    InitialSync(LevelState state, SyncKey key, ServerPlayer player, ChunkPos chunk, List<Session> sessions) {
      this.state = state;
      this.key = key;
      this.player = player;
      this.chunk = chunk;
      this.sessions = sessions;
    }

    @Override
    public int advance(ServerLevel level, int budget) {
      if (player.isRemoved()
          || player.serverLevel() != level
          || player.connection == null
          || state.initialSyncs.get(key) != this
          || !level.getChunkSource().chunkMap.getPlayers(chunk, false).contains(player)) {
        sessionIndex = sessions.size();
        return 1;
      }
      int used = 0;
      while (used < budget && sessionIndex < sessions.size()) {
        var session = sessions.get(sessionIndex);
        var positions = session.positions.get(chunk.toLong());
        if (session.removed || positions == null || positionIndex >= positions.size()) {
          sessionIndex++;
          positionIndex = 0;
          used++;
          continue;
        }
        int end = Math.min(positions.size(), positionIndex + Math.min(256, budget - used));
        MachineVisibilityPayloads.members(
            level,
            player,
            session.owner,
            session.generation,
            session.revision,
            session.active,
            chunk,
            positions.subList(positionIndex, end));
        used += end - positionIndex;
        positionIndex = end;
      }

      return used;
    }

    @Override
    public boolean done() {
      return sessionIndex >= sessions.size();
    }
  }

  private static final class LevelState {
    final Map<Long, Set<Session>> chunks = new HashMap<>();
    final ArrayDeque<Work> work = new ArrayDeque<>();
    final Map<SyncKey, InitialSync> initialSyncs = new HashMap<>();
  }

  private record SyncKey(UUID player, long chunk) {
  }
}
