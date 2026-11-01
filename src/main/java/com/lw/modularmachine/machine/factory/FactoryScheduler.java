package com.lw.modularmachine.machine.factory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded fair rounds. Waiting age precedes priority; GUI order stays stable by
 * ID.
 */
public final class FactoryScheduler {
  private final List<FactoryThreadState> channels;
  private final Map<String, FactoryThreadState> byId;
  private final Map<String, Integer> waiting;
  private List<String> roundOrder;
  private int cursor;

  public FactoryScheduler(List<FactoryThreadState> channels) {
    this(channels, 0);
  }

  public FactoryScheduler(List<FactoryThreadState> channels, int cursor) {
    this(channels, cursor, null, Map.of());
  }

  public FactoryScheduler(List<FactoryThreadState> channels, int cursor, List<String> roundOrder, Map<String, Integer> waiting) {
    Objects.requireNonNull(channels, "channels");
    if (channels.size() > FactoryThreadStateCodec.MAX_THREADS) {
      throw new IllegalArgumentException("Too many factory threads");
    }
    var copy = new ArrayList<>(channels);
    copy.sort(Comparator.comparing(FactoryThreadState::threadId));
    var index = new HashMap<String, FactoryThreadState>();
    for (var thread : copy) {
      if (index.put(thread.threadId(), thread) != null) {
        throw new IllegalArgumentException("Duplicate factory thread: " + thread.threadId());
      }
    }
    this.channels = List.copyOf(copy);
    byId = Map.copyOf(index);
    if (cursor < 0 || (copy.isEmpty() ? cursor != 0 : cursor >= copy.size())) {
      throw new IllegalArgumentException("Invalid factory scheduler cursor");
    }
    this.cursor = cursor;
    this.waiting = new HashMap<>();
    for (var entry : Objects.requireNonNull(waiting, "waiting").entrySet()) {
      if (!byId.containsKey(entry.getKey())
          || entry.getValue() == null
          || entry.getValue() < 0
          || entry.getValue() > FactoryThreadStateCodec.MAX_THREADS) {
        throw new IllegalArgumentException("Invalid factory waiting age");
      }
      if (entry.getValue() > 0) {
        this.waiting.put(entry.getKey(), entry.getValue());
      }
    }
    if (roundOrder != null && (roundOrder.size() != copy.size() || !new HashSet<>(roundOrder).equals(byId.keySet()))) {
      throw new IllegalArgumentException("Factory round order must contain every channel exactly once");
    }
    this.roundOrder = roundOrder == null ? ordered() : List.copyOf(roundOrder);
  }

  public List<FactoryThreadState> channels() {
    return channels;
  }

  /**
   * Current immutable state by stable ID, or null if the channel no longer
   * exists.
   */
  public FactoryThreadState channel(String threadId) {
    return byId.get(threadId);
  }

  public int cursor() {
    return cursor;
  }

  public List<String> roundOrder() {
    return roundOrder;
  }

  public Map<String, Integer> waitingAges() {
    return Map.copyOf(waiting);
  }

  public void setCursor(int cursor) {
    if (cursor < 0 || (channels.isEmpty() ? cursor != 0 : cursor >= channels.size())) {
      throw new IllegalArgumentException("Invalid factory scheduler cursor");
    }
    this.cursor = cursor;
  }

  private List<String> ordered() {
    return channels.stream()
        .sorted(
            Comparator.comparingInt((FactoryThreadState thread) -> waiting.getOrDefault(thread.threadId(), 0))
                .reversed().thenComparing(Comparator.comparingInt(FactoryThreadState::priority).reversed())
                .thenComparing(FactoryThreadState::threadId))
        .map(FactoryThreadState::threadId).toList();
  }

  public List<FactoryThreadState> visitWindow(int budget) {
    if (budget < 0) {
      throw new IllegalArgumentException("Negative scheduler budget");
    }
    var result = new ArrayList<FactoryThreadState>(Math.min(budget, channels.size()));
    for (int used = 0; used < budget && used < channels.size(); used++) {
      if (cursor == 0) {
        roundOrder = ordered();
      }
      result.add(byId.get(roundOrder.get(cursor)));
      cursor = (cursor + 1) % channels.size();
    }

    return List.copyOf(result);
  }

  public boolean recordAttempt(String threadId, boolean started) {
    if (!byId.containsKey(threadId)) {
      return false;
    }
    int before = waiting.getOrDefault(threadId, 0);
    int after = started ? 0 : Math.min(FactoryThreadStateCodec.MAX_THREADS, before + 1);
    if (after == before) {
      return false;
    }
    if (after == 0) {
      waiting.remove(threadId);
    } else {
      waiting.put(threadId, after);
    }

    return true;
  }

  public FactoryScheduler replace(FactoryThreadState replacement) {
    Objects.requireNonNull(replacement, "replacement");
    if (!byId.containsKey(replacement.threadId()) || byId.get(replacement.threadId()).equals(replacement)) {
      return this;
    }
    var updated = new ArrayList<>(channels);
    for (int index = 0; index < updated.size(); index++) {
      if (updated.get(index).threadId().equals(replacement.threadId())) {
        updated.set(index, replacement);
        break;
      }
    }

    return new FactoryScheduler(updated, cursor, cursor == 0 ? null : roundOrder, waiting);
  }

  public DispatchReport advance(int budget, Attempt attempt) {
    if (budget < 0) {
      throw new IllegalArgumentException("Negative scheduler budget");
    }
    Objects.requireNonNull(attempt, "attempt");
    var visited = new ArrayList<String>();
    FactoryThreadState started = null;
    int used = 0;
    while (used < budget && used < channels.size()) {
      var channel = visitWindow(1).getFirst();
      used++;
      if (!channel.enabled()) {
        continue;
      }
      visited.add(channel.threadId());
      var result = attempt.tryStart(channel);
      if (result != AttemptResult.INVALID) {
        recordAttempt(channel.threadId(), result == AttemptResult.STARTED);
      }
      if (result == AttemptResult.STARTED) {
        started = channel;
        break;
      }
    }

    return new DispatchReport(List.copyOf(visited), used, cursor, started);
  }

  @FunctionalInterface
  public interface Attempt {
    AttemptResult tryStart(FactoryThreadState channel);
  }

  public enum AttemptResult {
    STARTED,
    WAITING,
    BLOCKED,
    INVALID
  }

  public record DispatchReport(List<String> visited, int workUsed, int nextCursor, FactoryThreadState started) {
    public DispatchReport(List<String> visited, int workUsed, int nextCursor) {
      this(visited, workUsed, nextCursor, null);
    }

    public DispatchReport {
      visited = List.copyOf(visited);
      if (workUsed < 0 || nextCursor < 0) {
        throw new IllegalArgumentException("Invalid dispatch report");
      }
    }
  }
}
