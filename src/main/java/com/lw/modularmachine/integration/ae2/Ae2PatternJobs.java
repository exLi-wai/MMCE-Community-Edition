package com.lw.modularmachine.integration.ae2;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.lw.modularmachine.machine.network.NetworkPortService;
import com.lw.modularmachine.machine.network.pattern.PatternJobs;
import com.lw.modularmachine.machine.network.pattern.PatternResourceLedger.JobStatus;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal AE CPU links and narrowly scoped delivery context; never serialized.
 */
@ApiStatus.Internal
public final class Ae2PatternJobs {
  private static final Map<MinecraftServer, Tracker> TRACKERS = new IdentityHashMap<>();
  private static final Map<CraftingCpuLogic, Map<UUID, Boolean>> PENDING = new IdentityHashMap<>();
  private static final ThreadLocal<Push> PUSH = new ThreadLocal<>();

  private Ae2PatternJobs() {
  }

  public static void initialize() {
    PatternJobs.install(Ae2PatternJobs::canStart);
    NeoForge.EVENT_BUS.addListener(Ae2PatternJobs::stopped);
  }

  private static void stopped(ServerStoppedEvent event) {
    var tracker = TRACKERS.remove(event.getServer());
    if (tracker != null) {
      NetworkPortService.remove(tracker);
    }
    // No world-less proof can authorize another integrated server session.
    PENDING.clear();
    PUSH.remove();
  }

  public static void ensure(ServerLevel level) {
    tracker(level);
  }

  private static Tracker tracker(ServerLevel level) {
    ServerThreadGuard.require(level, "Pattern CPU association");
    return TRACKERS.computeIfAbsent(level.getServer(), server -> {
      var value = new Tracker(server, PatternJobs.get(level));
      NetworkPortService.add(value);
      return value;
    });
  }

  static Push context(Ae2PatternConnection provider) {
    var push = PUSH.get();

    return push != null && push.provider == provider && push.valid() ? push : null;
  }

  /**
   * Redirects only this mod's provider; foreign providers retain AE's exact call.
   */
  public static boolean push(CraftingCpuLogic cpu, CraftingCPUCluster cluster, ICraftingProvider provider, IPatternDetails pattern, KeyCounter[] inputs) {
    if (!(provider instanceof Ae2PatternConnection connection)) {
      return provider.pushPattern(pattern, inputs);
    }
    associated(cpu, cluster);
    var level = level(cluster);
    var link = cpu.getLastLink();
    if (level == null || link == null || link.isCanceled() || link.isDone()) {
      return false;
    }
    var location = new PatternJobs.Location(level.dimension().location(), cluster.getBoundsMin());
    var tracker = tracker(level);
    var context = new Push(connection, level, cluster, link, location, tracker);
    if (!context.valid() || !tracker.state.accepts(link.getCraftingID(), location)) {
      return false;
    }
    var previous = PUSH.get();
    PUSH.set(context);
    try {
      return provider.pushPattern(pattern, inputs);
    } finally {
      if (previous == null) {
        PUSH.remove();
      } else {
        PUSH.set(previous);
      }
    }
  }

  public static void finished(CraftingCpuLogic cpu, CraftingCPUCluster cluster, boolean success) {
    var link = cpu.getLastLink();
    if (link == null) {
      return;
    }
    var level = level(cluster);
    if (level == null) {
      PENDING.computeIfAbsent(cpu, ignored -> new LinkedHashMap<>()).putIfAbsent(link.getCraftingID(), success);
      return;
    }
    ServerThreadGuard.require(level, "Pattern CPU completion");
    PatternJobs.get(level).finish(link.getCraftingID(), success);
    associated(cpu, cluster);
  }

  /**
   * Also invoked after CPU restore and each CPU tick, including inactive CPUs.
   */
  public static void associated(CraftingCpuLogic cpu, CraftingCPUCluster cluster) {
    if (!PENDING.containsKey(cpu)) {
      return;
    }
    var level = level(cluster);
    if (level == null) {
      return;
    }
    var tracker = tracker(level);
    var proofs = PENDING.remove(cpu);
    proofs.forEach((id, success) -> tracker.proofs.addLast(new Proof(id, success)));
  }

  private static ServerLevel level(CraftingCPUCluster cluster) {
    var entities = cluster.getBlockEntities();
    if (!entities.hasNext()) {
      return null;
    }

    return entities.next().getLevel() instanceof ServerLevel level ? level : null;
  }

  private static boolean current(ServerLevel level, CraftingCPUCluster cluster, ICraftingLink link, PatternJobs.Location location) {
    if (cluster.isDestroyed()
        || !level.dimension().location().equals(location.dimension())
        || !level.hasChunkAt(location.position())
        || !(level.getBlockEntity(location.position()) instanceof CraftingBlockEntity block)
        || block.getCluster() != cluster
        || cluster.craftingLogic.getLastLink() != link) {
      return false;
    }

    return cluster.getNode() != null;
  }

  private static boolean canStart(ServerLevel level, PatternJobs.Job job) {
    var tracker = tracker(level);
    var live = tracker.resolve(job);
    if (live == null) {
      return false;
    }
    if (live.link.isCanceled() || live.link.isDone()) {
      tracker.state.finish(job.id(), !live.link.isCanceled());
      return false;
    }

    return true;
  }

  static final class Push {
    private final Ae2PatternConnection provider;
    private final ServerLevel level;
    private final CraftingCPUCluster cluster;
    private final ICraftingLink link;
    private final PatternJobs.Location location;
    private final Tracker tracker;

    private Push(Ae2PatternConnection provider, ServerLevel level, CraftingCPUCluster cluster, ICraftingLink link, PatternJobs.Location location, Tracker tracker) {
      this.provider = provider;
      this.level = level;
      this.cluster = cluster;
      this.link = link;
      this.location = location;
      this.tracker = tracker;
    }

    UUID job() {
      return link.getCraftingID();
    }

    boolean valid() {
      return current(level, cluster, link, location)
          && !link.isCanceled()
          && !link.isDone()
          && tracker.state.accepts(job(), location);
    }

    void accepted() {
      boolean known = tracker.state.job(job()) != null;
      tracker.state.accepted(job(), location);
      tracker.live.put(job(), new Live(level, cluster, link));
      if (!known) {
        tracker.queue.addLast(job());
      }
    }
  }

  private record Live(ServerLevel level, CraftingCPUCluster cluster, ICraftingLink link) {
  }

  private record Proof(UUID job, boolean success) {
  }

  private static final class Tracker implements NetworkPortService.Work {
    private final MinecraftServer server;
    private final PatternJobs state;
    private final ArrayDeque<UUID> queue = new ArrayDeque<>();
    private final ArrayDeque<Proof> proofs = new ArrayDeque<>();
    private final Map<UUID, Live> live = new HashMap<>();

    private Tracker(MinecraftServer server, PatternJobs state) {
      this.server = server;
      this.state = state;
      state.known().stream().filter(job -> job.status() == JobStatus.ACTIVE).forEach(job -> queue.addLast(job.id()));
    }

    @Override
    public MinecraftServer server() {
      return server;
    }

    @Override
    public boolean current() {
      return TRACKERS.get(server) == this;
    }

    private Live resolve(PatternJobs.Job job) {
      var value = live.get(job.id());
      if (value != null && Ae2PatternJobs.current(value.level, value.cluster, value.link, job.cpu())) {
        return value;
      }
      live.remove(job.id());
      var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, job.cpu().dimension()));
      if (level == null
          || !level.hasChunkAt(job.cpu().position())
          || !(level.getBlockEntity(job.cpu().position()) instanceof CraftingBlockEntity block)) {
        return null;
      }
      var cluster = block.getCluster();
      var link = cluster == null ? null : cluster.craftingLogic.getLastLink();
      if (link == null
          || !link.getCraftingID().equals(job.id())
          || !Ae2PatternJobs.current(level, cluster, link, job.cpu())) {
        return null;
      }
      value = new Live(level, cluster, link);
      live.put(job.id(), value);

      return value;
    }

    @Override
    public void networkStep() {
      if (!proofs.isEmpty()) {
        var proof = proofs.removeFirst();
        state.finish(proof.job(), proof.success());
        return;
      }
      if (queue.isEmpty()) {
        return;
      }
      var id = queue.removeFirst();
      var job = state.job(id);
      if (job == null || job.status() != JobStatus.ACTIVE) {
        live.remove(id);
        return;
      }
      var value = resolve(job);
      if (value != null && (value.link.isCanceled() || value.link.isDone())) {
        state.finish(id, !value.link.isCanceled());
        live.remove(id);
      } else {
        queue.addLast(id);
      }
    }
  }
}
