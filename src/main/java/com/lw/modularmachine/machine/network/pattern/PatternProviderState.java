package com.lw.modularmachine.machine.network.pattern;

import com.lw.modularmachine.machine.network.pattern.PatternResourceLedger.JobStatus;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal pattern/cache fragment of provider state. The GUI's two physical
 * item slots and fluid tank are separate storage, never CPU receipts. World,
 * callback, item portability and network authority belong to the owning BE.
 */
@ApiStatus.Internal
public final class PatternProviderState {
  public static final int SLOT_COUNT = 36;
  private final UUID instanceId = UUID.randomUUID();
  private final Runnable threadCheck;
  private final Runnable beforeWrite;
  private UUID providerId = UUID.randomUUID();
  private long revision;
  private long[] generations = new long[SLOT_COUNT];
  private Map<PatternContainerRef, Container> containers = new LinkedHashMap<>();
  private Tag unsupported;
  private Lease active;

  public record ContainerView(
      PatternContainerRef address,
      PatternDefinition pattern,
      long resourceRevision,
      boolean retired,
      PatternResourceLedger.Snapshot<PatternResourceKey> resources) {
  }

  public record ContainerMetadata(
      PatternContainerRef address,
      PatternDefinition pattern,
      long resourceRevision,
      boolean retired) {
  }

  /**
   * Detached immutable receipt maps; iterators retain only a previous state
   * generation.
   */
  public record NetworkShare(
      PatternContainerRef address,
      UUID job,
      JobStatus status,
      Map<PatternResourceKey, Long> resources) {
  }

  private static final class Container {
    private final PatternDefinition pattern;
    private final PatternResourceLedger<PatternResourceKey> resources;
    private long revision;
    private boolean retired;

    private Container(PatternDefinition pattern) {
      this.pattern = Objects.requireNonNull(pattern);
      resources = new PatternResourceLedger<>();
    }

    private Container(Container source) {
      pattern = source.pattern;
      resources = source.resources.copy();
      revision = source.revision;
      retired = source.retired;
    }

    private Container(PatternDefinition pattern, long revision, boolean retired, PatternResourceLedger<PatternResourceKey> resources) {
      this.pattern = pattern;
      this.revision = revision;
      this.retired = retired;
      this.resources = resources;
    }
  }

  public PatternProviderState(Runnable threadCheck, Runnable beforeWrite) {
    this.threadCheck = Objects.requireNonNull(threadCheck);
    this.beforeWrite = Objects.requireNonNull(beforeWrite);
  }

  public UUID instanceId() {
    return instanceId;
  }

  public UUID providerId() {
    readAllowed();

    return providerId;
  }

  public long revision() {
    readAllowed();

    return revision;
  }

  public boolean valid() {
    readAllowed();

    return unsupported == null;
  }

  public long generation(int slot) {
    readAllowed();
    requireSlot(slot);

    return generations[slot];
  }

  public Optional<ContainerView> current(int slot) {
    readAllowed();
    requireSlot(slot);
    if (unsupported != null || generations[slot] == 0) {
      return Optional.empty();
    }
    var ref = new PatternContainerRef(providerId, slot, generations[slot]);
    var container = containers.get(ref);

    return container == null ? Optional.empty() : Optional.of(view(ref, container));
  }

  public List<ContainerView> containers() {
    readAllowed();
    if (unsupported != null) {
      return List.of();
    }

    return containers.entrySet().stream().map(e -> view(e.getKey(), e.getValue())).toList();
  }

  public Optional<ContainerView> container(PatternContainerRef address) {
    readAllowed();
    var container = unsupported == null ? containers.get(address) : null;

    return container == null ? Optional.empty() : Optional.of(view(address, container));
  }

  public boolean matchesSource(ContainerView expected, UUID job) {
    readAllowed();
    var container = unsupported == null ? containers.get(expected.address()) : null;
    return container != null
        && container.retired == expected.retired()
        && container.pattern.equals(expected.pattern())
        && container.resources.matchesShare(expected.resources(), job);
  }

  public Optional<ContainerMetadata> currentMetadata(int slot) {
    readAllowed();
    requireSlot(slot);
    if (unsupported != null || generations[slot] == 0) {
      return Optional.empty();
    }
    var address = new PatternContainerRef(providerId, slot, generations[slot]);
    var container = containers.get(address);

    return container == null ? Optional.empty() : Optional
        .of(new ContainerMetadata(address, container.pattern, container.revision, container.retired));
  }

  public boolean contains(PatternContainerRef address) {
    readAllowed();

    return unsupported == null && containers.containsKey(address);
  }

  public long returnable(PatternContainerRef address, UUID job, PatternResourceKey key) {
    readAllowed();
    var container = unsupported == null ? containers.get(address) : null;

    return container == null ? 0 : container.resources.returnable(job, key);
  }

  public Iterator<NetworkShare> networkCursor() {
    readAllowed();
    if (unsupported != null) {
      return List.<NetworkShare>of().iterator();
    }
    // Each lease clones the map and every edited container. These old objects
    // remain immutable, so a suspended cursor neither scans nor blocks writers.
    return containers.entrySet().stream().flatMap(entry -> {
      var ledger = entry.getValue().resources;
      return ledger.networkShares()
          .map(share -> new NetworkShare(entry.getKey(), share.job(), share.status(), share.resources()));
    }).iterator();
  }

  /**
   * Item transport retains actual stock, but never transports run/CPU authority.
   */
  public Tag portableSave() {
    readAllowed();
    if (unsupported != null) {
      return unsupported.copy();
    }
    var portable = new PatternProviderState(threadCheck, () -> {
    });
    portable.generations = generations.clone();
    for (var entry : containers.entrySet()) {
      var previous = entry.getValue();
      var snapshot = previous.resources.snapshot();
      var receipts = new LinkedHashMap<UUID, PatternResourceLedger.Receipt<PatternResourceKey>>();
      snapshot.receipts().forEach(
          (job, receipt) -> receipts.put(
              job,
              new PatternResourceLedger.Receipt<>(
                  JobStatus.CANCELED,
                  receipt.delivered(),
                  receipt.remaining(),
                  Set.of())));
      var ledger = new PatternResourceLedger<>(
          new PatternResourceLedger.Snapshot<>(snapshot.physical(), snapshot.unowned(), receipts, Set.of(), true));
      if (!previous.retired) {
        ledger.completeReturn();
      }
      var address = new PatternContainerRef(portable.providerId, entry.getKey().slot(), entry.getKey().generation());
      portable.containers.put(address, new Container(previous.pattern, 0, previous.retired, ledger));
    }

    return portable.save();
  }

  private static ContainerView view(PatternContainerRef ref, Container container) {
    return new ContainerView(
        ref,
        container.pattern,
        container.revision,
        container.retired,
        container.resources.snapshot());
  }

  private void readAllowed() {
    threadCheck.run();
    if (active != null) {
      throw new IllegalStateException("Pattern provider is in a resource transaction");
    }
  }

  public boolean inTransaction() {
    threadCheck.run();

    return active != null;
  }

  /**
   * Identity and revision fences reject an old menu/ticket after load or BE
   * replacement even if its numeric revision happens to be the same.
   */
  public Lease acquire(UUID expectedInstance, long expectedRevision) {
    readAllowed();
    beforeWrite.run();
    readAllowed();
    if (unsupported != null
        || !instanceId.equals(expectedInstance)
        || revision != expectedRevision
        || revision == Long.MAX_VALUE) {
      throw new IllegalStateException("Stale or unsupported pattern provider state");
    }
    active = new Lease();

    return active;
  }

  /**
   * No content publisher is called here: all participating leases resolve first.
   */
  public final class Lease implements AutoCloseable {
    private final long[] nextGenerations = generations.clone();
    private final Map<PatternContainerRef, Container> next = new LinkedHashMap<>(containers);
    private final Set<PatternContainerRef> changed = new LinkedHashSet<>();
    private boolean modified;
    private boolean resolved;

    private Lease() {
    }

    private void requireActive() {
      threadCheck.run();
      if (resolved || active != this) {
        throw new IllegalStateException("Stale pattern provider lease");
      }
    }

    private Container editable(PatternContainerRef ref) {
      requireActive();
      var previous = next.get(ref);
      if (previous == null || previous.revision == Long.MAX_VALUE) {
        throw new IllegalStateException("Unavailable pattern generation");
      }
      if (changed.add(ref)) {
        previous = new Container(previous);
        next.put(ref, previous);
      }

      return previous;
    }

    private void changed(Container container) {
      if (container.revision == Long.MAX_VALUE) {
        throw new IllegalStateException("Pattern resource revision exhausted");
      }
      container.revision++;
      modified = true;
    }

    /** Null clears the slot; surviving old contents are retired and returnable. */
    public boolean setPattern(int slot, long expectedGeneration, PatternDefinition replacement) {
      requireActive();
      requireSlot(slot);
      if (nextGenerations[slot] != expectedGeneration || expectedGeneration == Long.MAX_VALUE) {
        return false;
      }
      var oldRef = expectedGeneration == 0 ? null : new PatternContainerRef(providerId, slot, expectedGeneration);
      var previous = oldRef == null ? null : next.get(oldRef);
      if (Objects.equals(previous == null ? null : previous.pattern, replacement)) {
        return true;
      }
      if (previous != null) {
        if (previous.revision == Long.MAX_VALUE) {
          return false;
        }
        var retired = editable(oldRef);
        retired.retired = true;
        retired.resources.requestReturn();
        changed(retired);
        if (retired.resources.empty()) {
          next.remove(oldRef);
        }
      }
      nextGenerations[slot]++;
      if (replacement != null) {
        var ref = new PatternContainerRef(providerId, slot, nextGenerations[slot]);
        next.put(ref, new Container(replacement));
        changed.add(ref);
      }
      modified = true;

      return true;
    }

    public boolean receive(PatternContainerRef ref, UUID job, Map<PatternResourceKey, Long> batch) {
      var container = editable(ref);
      if (container.retired || !container.resources.receive(job, batch)) {
        return false;
      }
      changed(container);

      return true;
    }

    public boolean consume(PatternContainerRef ref, UUID job, Map<PatternResourceKey, Long> batch) {
      var container = editable(ref);
      if (container.retired || !container.resources.consume(job, batch)) {
        return false;
      }
      changed(container);

      return true;
    }

    public boolean bindRun(PatternContainerRef ref, UUID job, UUID run) {
      var container = editable(ref);
      if (container.retired || runBound(run) || !container.resources.bindRun(job, run)) {
        return false;
      }
      changed(container);

      return true;
    }

    private boolean runBound(UUID run) {
      for (var container : next.values()) {
        if (container.resources.hasRun(run)) {
          return true;
        }
      }

      return false;
    }

    public boolean consumeForRun(PatternContainerRef ref, UUID job, UUID run, Map<PatternResourceKey, Long> batch) {
      var container = editable(ref);
      if (!container.resources.consumeForRun(job, run, batch)) {
        return false;
      }
      changed(container);

      return true;
    }

    public boolean releaseRun(PatternContainerRef ref, UUID job, UUID run) {
      var container = editable(ref);
      if (!container.resources.releaseRun(job, run)) {
        return false;
      }
      changed(container);
      prune(ref, container);

      return true;
    }

    public boolean finishJob(PatternContainerRef ref, UUID job, boolean success) {
      var container = editable(ref);
      if (!container.resources.finish(job, success)) {
        return false;
      }
      changed(container);

      return true;
    }

    public boolean requestReturn(PatternContainerRef ref) {
      var container = editable(ref);
      if (!container.resources.requestReturn()) {
        return false;
      }
      if (!container.retired) {
        container.resources.completeReturn();
      }
      changed(container);

      return true;
    }

    public long returnable(PatternContainerRef ref, UUID job, PatternResourceKey key) {
      requireActive();
      if (!next.containsKey(ref)) {
        return 0;
      }
      // Stage the private copy and revision fence before an external insertion.
      var container = editable(ref);

      return container.resources.returnable(job, key);
    }

    public boolean settleReturn(PatternContainerRef ref, UUID job, PatternResourceKey key, long accepted) {
      var container = editable(ref);
      if (!container.resources.settleReturn(job, key, accepted)) {
        return false;
      }
      if (accepted > 0) {
        changed(container);
        prune(ref, container);
      }

      return true;
    }

    private void prune(PatternContainerRef ref, Container container) {
      if (container.retired && container.resources.empty()) {
        next.remove(ref);
      } else if (!container.retired) {
        container.resources.completeReturn();
      }
    }

    public boolean modified() {
      return modified;
    }

    public void resolve(boolean commit) {
      requireActive();
      if (commit && modified) {
        generations = nextGenerations;
        containers = next;
        revision++;
      }
      resolved = true;
      active = null;
    }

    @Override
    public void close() {
      if (!resolved) {
        resolve(false);
      }
    }
  }

  /** This fragment is world state, not item-portable data or GUI authority. */
  public Tag save() {
    readAllowed();
    if (unsupported != null) {
      return unsupported.copy();
    }
    var root = new CompoundTag();
    root.putInt("schema", 1);
    root.putUUID("provider", providerId);
    root.putLong("revision", revision);
    root.put("generations", new LongArrayTag(generations.clone()));
    var list = new ListTag();
    containers.forEach((ref, container) -> {
      var entry = new CompoundTag();
      entry.putInt("slot", ref.slot());
      entry.putLong("generation", ref.generation());
      entry.put("pattern", container.pattern.save());
      entry.putLong("revision", container.revision);
      entry.putBoolean("retired", container.retired);
      entry.put("resources", saveLedger(container.resources.snapshot()));
      list.add(entry);
    });
    root.put("containers", list);

    return root;
  }

  public static PatternProviderState load(Tag value, HolderLookup.Provider registries, Runnable threadCheck, Runnable beforeWrite) {
    var state = new PatternProviderState(threadCheck, beforeWrite);
    threadCheck.run();
    if (value == null) {
      return state;
    }
    try {
      if (!(value instanceof CompoundTag root)
          || !root.contains("schema", Tag.TAG_INT)
          || root.getInt("schema") != 1
          || !root.hasUUID("provider")
          || !root.contains("generations", Tag.TAG_LONG_ARRAY)) {
        throw new IllegalArgumentException("Unsupported pattern container state");
      }
      state.providerId = root.getUUID("provider");
      state.revision = nonnegative(root, "revision");
      state.generations = root.getLongArray("generations").clone();
      if (state.generations.length != SLOT_COUNT || Arrays.stream(state.generations).anyMatch(v -> v < 0)) {
        throw new IllegalArgumentException("Invalid pattern generations");
      }
      var runs = new LinkedHashSet<UUID>();
      for (var tag : compounds(root, "containers")) {
        var entry = (CompoundTag) tag;
        if (!entry.contains("slot", Tag.TAG_INT)) {
          throw new IllegalArgumentException("Missing pattern slot");
        }
        var ref = new PatternContainerRef(state.providerId, entry.getInt("slot"), positive(entry, "generation"));
        boolean retired = bool(entry, "retired");
        if (ref.generation() > state.generations[ref.slot()]
            || retired != (ref.generation() < state.generations[ref.slot()])) {
          throw new IllegalArgumentException("Invalid live/retired pattern generation");
        }
        var ledger = loadLedger(compound(entry, "resources"), registries);
        var snapshot = ledger.snapshot();
        if (retired && !snapshot.returnRequested()) {
          throw new IllegalArgumentException("Retired pattern cache has no return marker");
        }
        var held = new ArrayList<>(snapshot.unownedRuns());
        snapshot.receipts().values().forEach(receipt -> held.addAll(receipt.runs()));
        for (var run : held) {
          if (!runs.add(run)) {
            throw new IllegalArgumentException("Pattern run bound to multiple generations");
          }
        }
        var container = new Container(
            PatternDefinition.load(compound(entry, "pattern"), registries),
            nonnegative(entry, "revision"),
            retired,
            ledger);
        if (state.containers.putIfAbsent(ref, container) != null) {
          throw new IllegalArgumentException("Duplicate pattern generation");
        }
      }
    } catch (RuntimeException invalid) {
      // Preserve the entire fragment. Never turn an undecodable receipt into
      // unowned material or silently discard the physical cache.
      state.unsupported = value.copy();
      state.containers.clear();
    }

    return state;
  }

  private static CompoundTag saveLedger(PatternResourceLedger.Snapshot<PatternResourceKey> snapshot) {
    var root = new CompoundTag();
    root.put("physical", saveAmounts(snapshot.physical()));
    root.put("unowned", saveAmounts(snapshot.unowned()));
    root.putBoolean("return_requested", snapshot.returnRequested());
    root.put("unowned_runs", saveRuns(snapshot.unownedRuns()));
    var receipts = new ListTag();
    snapshot.receipts().forEach((job, receipt) -> {
      var entry = new CompoundTag();
      entry.putUUID("job", job);
      entry.putString("status", receipt.status().name());
      entry.put("delivered", saveAmounts(receipt.delivered()));
      entry.put("remaining", saveAmounts(receipt.remaining()));
      entry.put("runs", saveRuns(receipt.runs()));
      receipts.add(entry);
    });
    root.put("receipts", receipts);

    return root;
  }

  private static PatternResourceLedger<PatternResourceKey> loadLedger(CompoundTag root, HolderLookup.Provider registries) {
    var receipts = new LinkedHashMap<UUID, PatternResourceLedger.Receipt<PatternResourceKey>>();
    for (var tag : compounds(root, "receipts")) {
      var entry = (CompoundTag) tag;
      if (!entry.hasUUID("job") || !entry.contains("status", Tag.TAG_STRING)) {
        throw new IllegalArgumentException("Missing pattern receipt identity");
      }
      var receipt = new PatternResourceLedger.Receipt<>(
          JobStatus.valueOf(entry.getString("status")),
          loadAmounts(entry, "delivered", registries),
          loadAmounts(entry, "remaining", registries),
          loadRuns(entry, "runs"));
      if (receipts.putIfAbsent(entry.getUUID("job"), receipt) != null) {
        throw new IllegalArgumentException("Duplicate pattern receipt");
      }
    }

    return new PatternResourceLedger<>(
        new PatternResourceLedger.Snapshot<>(
            loadAmounts(root, "physical", registries),
            loadAmounts(root, "unowned", registries),
            receipts,
            loadRuns(root, "unowned_runs"),
            bool(root, "return_requested")));
  }

  private static ListTag saveAmounts(Map<PatternResourceKey, Long> amounts) {
    var result = new ListTag();
    amounts.forEach((key, amount) -> {
      var row = new CompoundTag();
      row.put("key", key.save());
      row.putLong("amount", amount);
      result.add(row);
    });

    return result;
  }

  private static Map<PatternResourceKey, Long> loadAmounts(CompoundTag root, String field, HolderLookup.Provider registries) {
    var result = new LinkedHashMap<PatternResourceKey, Long>();
    for (var tag : compounds(root, field)) {
      var row = (CompoundTag) tag;
      var key = PatternResourceKey.load(compound(row, "key"), registries);
      if (result.putIfAbsent(key, positive(row, "amount")) != null) {
        throw new IllegalArgumentException("Duplicate pattern resource entry");
      }
    }

    return result;
  }

  private static ListTag saveRuns(Set<UUID> runs) {
    var result = new ListTag();
    runs.forEach(run -> {
      var entry = new CompoundTag();
      entry.putUUID("run", run);
      result.add(entry);
    });

    return result;
  }

  private static Set<UUID> loadRuns(CompoundTag root, String field) {
    var runs = new LinkedHashSet<UUID>();
    for (var tag : compounds(root, field)) {
      var entry = (CompoundTag) tag;
      if (!entry.hasUUID("run") || !runs.add(entry.getUUID("run"))) {
        throw new IllegalArgumentException("Missing or duplicate pattern run");
      }
    }

    return runs;
  }

  private static CompoundTag compound(CompoundTag root, String field) {
    if (!root.contains(field, Tag.TAG_COMPOUND)) {
      throw new IllegalArgumentException("Missing pattern compound: " + field);
    }

    return root.getCompound(field);
  }

  private static ListTag compounds(CompoundTag root, String field) {
    if (!(root.get(field) instanceof ListTag list) || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) {
      throw new IllegalArgumentException("Invalid pattern list: " + field);
    }

    return list;
  }

  private static long nonnegative(CompoundTag root, String field) {
    if (!root.contains(field, Tag.TAG_LONG) || root.getLong(field) < 0) {
      throw new IllegalArgumentException("Invalid pattern revision/quantity: " + field);
    }

    return root.getLong(field);
  }

  private static long positive(CompoundTag root, String field) {
    long result = nonnegative(root, field);
    if (result == 0) {
      throw new IllegalArgumentException("Empty pattern quantity/generation: " + field);
    }

    return result;
  }

  private static boolean bool(CompoundTag root, String field) {
    if (!root.contains(field, Tag.TAG_BYTE) || root.getByte(field) < 0 || root.getByte(field) > 1) {
      throw new IllegalArgumentException("Invalid pattern flag: " + field);
    }

    return root.getBoolean(field);
  }

  private static void requireSlot(int slot) {
    if (slot < 0 || slot >= SLOT_COUNT) {
      throw new IllegalArgumentException("Invalid pattern slot");
    }
  }
}
