package com.lw.modularmachine.machine.network.pattern;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal physical cache and CPU shares for one pattern generation. Keys must
 * be immutable exact identities. This ledger has no network or world callbacks;
 * its owner edits a private copy under the provider lease.
 */
@ApiStatus.Internal
public final class PatternResourceLedger<K> {
  public enum JobStatus {
    ACTIVE,
    COMPLETED,
    CANCELED
  }

  public record Receipt<K>(JobStatus status, Map<K, Long> delivered, Map<K, Long> remaining, Set<UUID> runs) {
    public Receipt {
      Objects.requireNonNull(status, "status");
      delivered = amounts(delivered);
      remaining = amounts(remaining);
      runs = Collections.unmodifiableSet(new LinkedHashSet<>(runs));
      if (runs.contains(null) || !covers(delivered, remaining)) {
        throw new IllegalArgumentException("Invalid pattern receipt");
      }
    }
  }

  public record Snapshot<K>(
      Map<K, Long> physical,
      Map<K, Long> unowned,
      Map<UUID, Receipt<K>> receipts,
      Set<UUID> unownedRuns,
      boolean returnRequested) {
    public Snapshot {
      physical = amounts(physical);
      unowned = amounts(unowned);
      receipts = Collections.unmodifiableMap(new LinkedHashMap<>(receipts));
      unownedRuns = Collections.unmodifiableSet(new LinkedHashSet<>(unownedRuns));
      if (receipts.containsKey(null) || receipts.containsValue(null) || unownedRuns.contains(null)) {
        throw new IllegalArgumentException("Invalid pattern ledger identity");
      }
      var sum = new LinkedHashMap<>(unowned);
      var runs = new LinkedHashSet<>(unownedRuns);
      for (var receipt : receipts.values()) {
        add(sum, receipt.remaining());
        for (var run : receipt.runs()) {
          if (!runs.add(run)) {
            throw new IllegalArgumentException("Run belongs to multiple pattern receipts");
          }
        }
      }
      if (!physical.equals(sum)) {
        throw new IllegalArgumentException("Pattern physical cache does not equal receipt shares");
      }
    }
  }

  private final Map<K, Long> physical = new LinkedHashMap<>();
  private final Map<K, Long> unowned = new LinkedHashMap<>();
  private final Map<UUID, Receipt<K>> receipts = new LinkedHashMap<>();
  private final Set<UUID> unownedRuns = new LinkedHashSet<>();
  private boolean returnRequested;

  public PatternResourceLedger() {
  }

  public PatternResourceLedger(Snapshot<K> snapshot) {
    physical.putAll(snapshot.physical());
    unowned.putAll(snapshot.unowned());
    receipts.putAll(snapshot.receipts());
    unownedRuns.addAll(snapshot.unownedRuns());
    returnRequested = snapshot.returnRequested();
  }

  public PatternResourceLedger<K> copy() {
    // Existing state has already been validated; no full balance scan per push.
    var result = new PatternResourceLedger<K>();
    result.physical.putAll(physical);
    result.unowned.putAll(unowned);
    result.receipts.putAll(receipts);
    result.unownedRuns.addAll(unownedRuns);
    result.returnRequested = returnRequested;

    return result;
  }

  public Snapshot<K> snapshot() {
    return new Snapshot<>(physical, unowned, receipts, unownedRuns, returnRequested);
  }

  /** Search dependencies belong to one receipt, not the whole physical cache. */
  public boolean matchesShare(Snapshot<K> expected, UUID job) {
    return returnRequested == expected.returnRequested()
        && (job == null ? unowned.equals(expected.unowned()) && unownedRuns.equals(expected.unownedRuns()) : Objects
            .equals(receipts.get(job), expected.receipts().get(job)));
  }

  record NetworkShare<K>(UUID job, JobStatus status, Map<K, Long> resources) {
  }

  Stream<NetworkShare<K>> networkShares() {
    return Stream.concat(
        Stream.of(new NetworkShare<>(null, null, Collections.unmodifiableMap(unowned))),
        receipts.entrySet().stream()
            .map(entry -> new NetworkShare<>(entry.getKey(), entry.getValue().status(), entry.getValue().remaining())));
  }

  public boolean empty() {
    return physical.isEmpty() && unownedRuns.isEmpty() && receipts.values().stream().allMatch(r -> r.runs().isEmpty());
  }

  public Map<K, Long> physical() {
    return amounts(physical);
  }

  public boolean hasRun(UUID run) {
    return unownedRuns.contains(run) || receipts.values().stream().anyMatch(r -> r.runs().contains(run));
  }

  public Map<K, Long> available(UUID job) {
    if (returnRequested) {
      return Map.of();
    }
    if (job == null) {
      return amounts(unowned);
    }
    var receipt = receipts.get(job);

    return receipt == null || receipt.status() != JobStatus.ACTIVE ? Map.of() : receipt.remaining();
  }

  /**
   * Whole-batch admission. An overflow or terminal job leaves every map intact.
   */
  public boolean receive(UUID job, Map<K, Long> batch) {
    var incoming = nonempty(batch);
    var previous = job == null ? null : receipts.get(job);
    if (returnRequested || previous != null && previous.status() != JobStatus.ACTIVE) {
      return false;
    }
    var owned = previous == null ? Map.<K, Long>of() : previous.remaining();
    var delivered = previous == null ? Map.<K, Long>of() : previous.delivered();
    if (!canAdd(physical, incoming)
        || job == null && !canAdd(unowned, incoming)
        || job != null && (!canAdd(owned, incoming) || !canAdd(delivered, incoming))) {
      return false;
    }
    if (job == null) {
      add(unowned, incoming);
    } else {
      var next = new LinkedHashMap<>(owned);
      var total = new LinkedHashMap<>(delivered);
      add(next, incoming);
      add(total, incoming);
      receipts.put(job, new Receipt<>(JobStatus.ACTIVE, total, next, previous == null ? Set.of() : previous.runs()));
    }
    add(physical, incoming);

    return true;
  }

  /** Startup debit; another CPU's shares are never candidates for this scope. */
  public boolean consume(UUID job, Map<K, Long> requested) {
    var batch = nonempty(requested);
    if (!covers(available(job), batch)) {
      return false;
    }
    debit(job, batch);

    return true;
  }

  /** A run holds its source, without prepaying any future per-tick resource. */
  public boolean bindRun(UUID job, UUID run) {
    Objects.requireNonNull(run, "run");
    var receipt = job == null ? null : receipts.get(job);
    if (returnRequested || job != null && (receipt == null || receipt.status() != JobStatus.ACTIVE) || hasRun(run)) {
      return false;
    }
    if (job == null) {
      unownedRuns.add(run);
    } else {
      var runs = new LinkedHashSet<>(receipt.runs());
      runs.add(run);
      receipts.put(job, new Receipt<>(receipt.status(), receipt.delivered(), receipt.remaining(), runs));
    }

    return true;
  }

  /** Cancellation and manual return do not disable an already-paid run. */
  public boolean consumeForRun(UUID job, UUID run, Map<K, Long> requested) {
    var batch = nonempty(requested);
    var receipt = job == null ? null : receipts.get(job);
    boolean bound = job == null ? unownedRuns.contains(run) : receipt != null && receipt.runs().contains(run);
    var source = job == null ? unowned : receipt == null ? Map.<K, Long>of() : receipt.remaining();
    if (!bound || !covers(source, batch)) {
      return false;
    }
    debit(job, batch);

    return true;
  }

  public boolean releaseRun(UUID job, UUID run) {
    if (job == null) {
      return unownedRuns.remove(run);
    }
    var receipt = receipts.get(job);
    if (receipt == null || !receipt.runs().contains(run)) {
      return false;
    }
    var runs = new LinkedHashSet<>(receipt.runs());
    runs.remove(run);
    receipts.put(job, new Receipt<>(receipt.status(), receipt.delivered(), receipt.remaining(), runs));

    return true;
  }

  /** Creates an empty terminal marker too, so later delivery cannot reopen it. */
  public boolean finish(UUID job, boolean success) {
    Objects.requireNonNull(job, "job");
    var previous = receipts.get(job);
    var status = success ? JobStatus.COMPLETED : JobStatus.CANCELED;
    if (previous != null && (previous.status() == JobStatus.CANCELED || previous.status() == status)) {
      return false;
    }
    receipts.put(
        job,
        new Receipt<>(
            status,
            previous == null ? Map.of() : previous.delivered(),
            previous == null ? Map.of() : previous.remaining(),
            previous == null ? Set.of() : previous.runs()));

    return true;
  }

  public boolean requestReturn() {
    if (returnRequested) {
      return false;
    }
    returnRequested = true;

    return true;
  }

  /** A drained current slot may accept a new order after manual return. */
  public boolean completeReturn() {
    if (!returnRequested || !empty()) {
      return false;
    }
    returnRequested = false;

    return true;
  }

  /**
   * Conservative hold: no share of a source is returned until its runs release.
   */
  public long returnable(UUID job, K key) {
    Objects.requireNonNull(key, "key");
    if (job == null) {
      return returnRequested && unownedRuns.isEmpty() ? unowned.getOrDefault(key, 0L) : 0;
    }
    var receipt = receipts.get(job);
    if (receipt == null || !receipt.runs().isEmpty() || !returnRequested && receipt.status() != JobStatus.CANCELED) {
      return 0;
    }

    return receipt.remaining().getOrDefault(key, 0L);
  }

  /** Called inside the same lease after poweredInsert, with its actual result. */
  public boolean settleReturn(UUID job, K key, long accepted) {
    if (accepted < 0 || accepted > returnable(job, key)) {
      return false;
    }
    if (accepted > 0) {
      debit(job, Map.of(key, accepted));
    }

    return true;
  }

  private void debit(UUID job, Map<K, Long> batch) {
    if (job == null) {
      subtract(unowned, batch);
    } else {
      var previous = receipts.get(job);
      var next = new LinkedHashMap<>(previous.remaining());
      subtract(next, batch);
      receipts.put(job, new Receipt<>(previous.status(), previous.delivered(), next, previous.runs()));
    }
    subtract(physical, batch);
  }

  private static <K> Map<K, Long> nonempty(Map<K, Long> source) {
    var result = amounts(source);
    if (result.isEmpty()) {
      throw new IllegalArgumentException("Empty pattern input batch");
    }

    return result;
  }

  private static <K> Map<K, Long> amounts(Map<K, Long> source) {
    var result = new LinkedHashMap<K, Long>();
    source.forEach((key, amount) -> {
      if (key == null || amount == null || amount <= 0) {
        throw new IllegalArgumentException("Invalid pattern resource quantity");
      }
      result.put(key, amount);
    });

    return Collections.unmodifiableMap(result);
  }

  private static <K> boolean covers(Map<K, Long> source, Map<K, Long> requested) {
    return requested.entrySet().stream().allMatch(e -> source.getOrDefault(e.getKey(), 0L) >= e.getValue());
  }

  private static <K> boolean canAdd(Map<K, Long> source, Map<K, Long> added) {
    return added.entrySet().stream()
        .allMatch(e -> source.getOrDefault(e.getKey(), 0L) <= Long.MAX_VALUE - e.getValue());
  }

  private static <K> void add(Map<K, Long> target, Map<K, Long> batch) {
    batch.forEach((key, amount) -> target.merge(key, amount, Math::addExact));
  }

  private static <K> void subtract(Map<K, Long> target, Map<K, Long> batch) {
    batch.forEach((key, amount) -> {
      long next = Math.subtractExact(target.getOrDefault(key, 0L), amount);
      if (next < 0) {
        throw new IllegalStateException("Pattern cache underflow");
      }
      if (next == 0) {
        target.remove(key);
      } else {
        target.put(key, next);
      }
    });
  }
}
