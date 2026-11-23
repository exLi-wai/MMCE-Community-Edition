package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.structure.StructureService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;

/**
 * Detached item and fluid planning sharing one round-robin allowance per
 * controller tick.
 */
@ApiStatus.Internal
public final class TickOutputWork {
  private static final int WORK = 64;
  private static final Map<StructureService.StructureSubject, TickOutputWork> OWNERS = new WeakHashMap<>();

  public interface Plan {
    int advance(int budget);
    boolean pending();
  }

  private record Key(Class<? extends Plan> resource, UUID run) {
  }

  private static final class Entry {
    Plan plan;
    long visited;
  }

  private final Map<Key, Entry> plans = new HashMap<>();
  private final LinkedHashSet<Key> queue = new LinkedHashSet<>();
  private long tick = Long.MIN_VALUE;
  private int remaining;

  public static TickOutputWork forOwner(StructureService.StructureSubject owner) {
    return OWNERS.computeIfAbsent(owner, ignored -> new TickOutputWork());
  }

  public static void beginTick(StructureService.StructureSubject owner, long tick, Supplier<Stream<UUID>> paidRuns) {
    var work = OWNERS.get(owner);
    if (work != null) {
      work.retainPaidRuns(tick, paidRuns);
      work.begin(tick);
      if (work.plans.isEmpty() && work.remaining == WORK) {
        OWNERS.remove(owner);
      }
    }
  }

  public static void forget(StructureService.StructureSubject owner) {
    if (owner.structureLevel() instanceof ServerLevel level && level.getServer().isSameThread()) {
      OWNERS.remove(owner);
    }
  }

  static void clear() {
    OWNERS.clear();
  }

  void begin(long now) {
    if (now == tick) {
      return;
    }
    tick = now;
    remaining = WORK;
  }

  void retainPaidRuns(long now, Supplier<Stream<UUID>> paidRuns) {
    var absent = new HashSet<UUID>();
    for (var entry : plans.entrySet()) {
      if (entry.getValue().visited < now - 1) {
        absent.add(entry.getKey().run());
      }
    }
    if (absent.isEmpty()) {
      return;
    }
    // A skipped visit can mean PRE_TICK paused the batch. Consult the owner's
    // paid runs once for all skipped plans, stopping when each has been found.
    var runs = paidRuns.get().iterator();
    while (!absent.isEmpty() && runs.hasNext()) {
      absent.remove(runs.next());
    }
    if (!absent.isEmpty()) {
      plans.keySet().removeIf(key -> absent.contains(key.run()));
      queue.removeIf(key -> absent.contains(key.run()));
    }
  }

  public <T extends Plan> T visit(long now, Class<T> type, UUID firstRun, Predicate<T> current, Supplier<T> create) {
    begin(now);
    var key = new Key(type, firstRun);
    var entry = plans.computeIfAbsent(key, ignored -> new Entry());
    entry.visited = now;
    T plan = type.cast(entry.plan);
    if (plan == null || !current.test(plan)) {
      plan = create.get();
      entry.plan = plan;
      queue.add(key);
    }
    // Only detached work is serviced here. Its owner checks the images again
    // before materializing changes.
    int count = queue.size();
    while (remaining > 0 && count-- > 0) {
      var next = queue.removeFirst();
      var nextPlan = plans.get(next).plan;
      if (nextPlan.pending()) {
        remaining -= nextPlan.advance(remaining);
        if (nextPlan.pending()) {
          queue.add(next);
        }
      }
    }
    return plan;
  }
}
