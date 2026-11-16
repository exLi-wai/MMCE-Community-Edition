package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.machine.upgrade.UpgradeLifecyclePublisher;
import com.lw.modularmachine.machine.recipe.RecipeLifecyclePublisher;
import com.lw.modularmachine.machine.smart.SmartInterfaceEventPublisher;
import com.lw.modularmachine.machine.structure.StructureService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Owns one factory's thread configuration and scheduler; platform effects stay
 * on its host.
 */
@ApiStatus.Internal
public final class FactoryThreadConfiguration {
  private final Host host;
  private FactoryScheduler scheduler = new FactoryScheduler(List.of());
  private boolean threadsInitialized;
  private long configurationRevision;

  public FactoryThreadConfiguration(Host host) {
    this.host = Objects.requireNonNull(host, "host");
  }

  /**
   * Narrow platform operations; never exposes inventory, leases or arbitrary
   * controller writes.
   */
  public interface Host {
    void requireThread();
    void requireUpdateThread();
    boolean persistentStateValid();
    boolean current();
    boolean dispatching();
    ResourceLocation machineId();
    boolean acceptsMachine(ResourceLocation machine);
    void bindMachine(ResourceLocation machine);
    boolean hasPaidRuns();
    boolean hasPaidRuns(String threadId);
    boolean hasStartingThreads();
    boolean threadBusy(String threadId);
    int maximumThreads();
    boolean recipeBelongsTo(ResourceLocation machine, ResourceLocation recipe);
    void beforeInstall();
    void installed();
    void threadChanged(String threadId);
    void markChanged();
  }

  /**
   * Complete intent for one atomic update; null optionals leave that property
   * unchanged.
   */
  public record Change(
      boolean changeLock,
      ResourceLocation lockedRecipe,
      Boolean enabled,
      Integer parallelLimit,
      Long upgradeRevision) {
    public static Change lockedRecipe(ResourceLocation recipe) {
      return new Change(true, recipe, null, null, null);
    }

    public static Change enabled(boolean enabled) {
      return new Change(false, null, enabled, null, null);
    }

    public static Change parallelLimit(int limit, long upgradeRevision) {
      return new Change(false, null, null, limit, upgradeRevision);
    }
  }

  public FactoryScheduler scheduler() {
    return scheduler;
  }

  public List<FactoryThreadState> visitWindow(int budget) {
    int previousCursor = scheduler.cursor();
    var previousOrder = scheduler.roundOrder();
    var window = scheduler.visitWindow(budget);
    if (previousCursor != scheduler.cursor() || !previousOrder.equals(scheduler.roundOrder())) {
      host.markChanged();
    }

    return window;
  }

  public void recordAttempt(String threadId, boolean started) {
    if (scheduler.recordAttempt(threadId, started)) {
      host.markChanged();
    }
  }

  /**
   * Runtime state transitions retain the configuration watermark and installed
   * flag.
   */
  public void replaceRuntime(FactoryScheduler replacement) {
    scheduler = Objects.requireNonNull(replacement, "replacement");
  }

  public void resetState() {
    scheduler = new FactoryScheduler(List.of());
    threadsInitialized = false;
    configurationRevision = 0;
  }

  public void save(CompoundTag tag) {
    FactoryThreadConfigurationCodec.write(tag, scheduler, threadsInitialized, configurationRevision);
  }

  public void load(CompoundTag tag) {
    var loaded = FactoryThreadConfigurationCodec.read(tag);
    scheduler = loaded.scheduler();
    threadsInitialized = loaded.initialized();
    configurationRevision = loaded.revision();
  }

  public static boolean hasSavedFields(CompoundTag tag) {
    return FactoryThreadConfigurationCodec.hasFields(tag);
  }

  public void replaceThreads(List<FactoryThreadState> threads) {
    if (!configurationAllowed() || host.hasPaidRuns() || host.hasStartingThreads()) {
      throw new IllegalStateException("Factory configuration is unavailable or owns paid runs");
    }
    installConfiguration(new FactoryScheduler(threads));
  }

  public enum ConfigurationResult {
    SUCCESS,
    NO_CHANGE,
    UNAVAILABLE,
    BUSY,
    UNKNOWN_DEFINITION,
    THREAD_NOT_FOUND,
    INVALID,
    LIMIT_REACHED
  }

  public boolean initialized() {
    return threadsInitialized;
  }

  public long revision() {
    return configurationRevision;
  }

  /**
   * Internal whitelist snapshot; paid runs and installed upgrades are not copied.
   */
  @ApiStatus.Internal
  public Optional<FactoryThreadPreset> copyPreset() {
    host.requireThread();
    if (!host.persistentStateValid()
        || !threadsInitialized
        || !host.current()
        || StructureService.definition(host.machineId()) == null) {
      return Optional.empty();
    }
    return Optional.of(
        new FactoryThreadPreset(
            scheduler.channels().stream()
                .map(
                    thread -> new FactoryThreadPreset.Thread(
                        thread.threadId(),
                        thread.lockedRecipe(),
                        thread.enabled(),
                        thread.priority(),
                        thread.parallelLimit()))
                .toList()));
  }

  /**
   * Internal atomic configuration paste. Compatibility metadata is checked, never
   * installed as identity.
   */
  @ApiStatus.Internal
  public ConfigurationResult pastePreset(ResourceLocation expectedMachine, long expectedRevision, FactoryThreadPreset preset) {
    if (!configurationAllowed()
        || UpgradeLifecyclePublisher.isDispatchingAny()
        || RecipeLifecyclePublisher.isDispatching()
        || SmartInterfaceEventPublisher.isDispatching()) {
      return ConfigurationResult.UNAVAILABLE;
    }
    if (!host.machineId().equals(expectedMachine) || configurationRevision != expectedRevision || preset == null) {
      return ConfigurationResult.INVALID;
    }
    if (host.hasPaidRuns() || host.hasStartingThreads()) {
      return ConfigurationResult.BUSY;
    }
    var definition = StructureService.definition(host.machineId());
    if (definition == null) {
      return ConfigurationResult.UNKNOWN_DEFINITION;
    }
    if (preset.threads().size() > definition.maximumFactoryThreads()) {
      return ConfigurationResult.LIMIT_REACHED;
    }
    long epoch = StructureService.definitionEpoch();
    long revision = configurationRevision;
    for (var current : scheduler.channels()) {
      revision = Math.max(revision, current.revision());
    }
    if (revision >= Long.MAX_VALUE - 1) {
      return ConfigurationResult.UNAVAILABLE;
    }
    var copied = new ArrayList<FactoryThreadState>();
    for (var thread : preset.threads()) {
      if (thread.lockedRecipe() != null && !host.recipeBelongsTo(host.machineId(), thread.lockedRecipe())) {
        return ConfigurationResult.INVALID;
      }
      copied.add(thread.instantiate(host.machineId()));
    }
    if (configurationRevision != expectedRevision
        || !configurationAllowed()
        || host.hasPaidRuns()
        || host.hasStartingThreads()
        || StructureService.definitionEpoch() != epoch
        || StructureService.definition(host.machineId()) != definition
        || !host.machineId().equals(expectedMachine)) {
      return ConfigurationResult.UNAVAILABLE;
    }
    var current = copyPreset();
    if (current.isPresent() && current.get().equals(preset)) {
      return ConfigurationResult.NO_CHANGE;
    }
    installConfiguration(new FactoryScheduler(copied));

    return ConfigurationResult.SUCCESS;
  }

  private boolean configurationAllowed() {
    host.requireThread();

    return host.persistentStateValid()
        && configurationRevision < Long.MAX_VALUE
        && scheduler.channels().stream().noneMatch(thread -> thread.revision() == Long.MAX_VALUE)
        && !host.dispatching()
        && host.current();
  }

  private void installConfiguration(FactoryScheduler replacement) {
    if (replacement.channels().size() > host.maximumThreads()) {
      var existing = scheduler.channels().stream().map(FactoryThreadState::threadId).collect(Collectors.toSet());
      if (replacement.channels().size() >= scheduler.channels().size()
          || replacement.channels().stream().anyMatch(thread -> !existing.contains(thread.threadId()))) {
        throw new IllegalArgumentException("Factory configuration exceeds its machine business thread limit");
      }
    }
    // New channel identities must exceed every retired revision, even after an ID
    // is removed and later reused. Retained channels keep their paid runtime state.
    long highestRevision = configurationRevision;
    for (var thread : scheduler.channels()) {
      highestRevision = Math.max(highestRevision, thread.revision());
    }
    for (var thread : replacement.channels()) {
      highestRevision = Math.max(highestRevision, thread.revision());
    }
    long nextRevision = Math.incrementExact(highestRevision);
    var installed = new ArrayList<FactoryThreadState>();
    for (var thread : replacement.channels()) {
      installed.add(scheduler.channel(thread.threadId()) == thread ? thread : installedThread(thread, nextRevision));
    }
    var next = new FactoryScheduler(installed);
    host.beforeInstall();
    scheduler = next;
    threadsInitialized = true;
    configurationRevision = nextRevision;
    host.installed();
  }

  private static FactoryThreadState installedThread(FactoryThreadState thread, long revision) {
    return new FactoryThreadState(
        thread.threadId(),
        thread.machineId(),
        thread.lockedRecipe(),
        thread.enabled(),
        thread.priority(),
        thread.parallelLimit(),
        thread.upgradeRevision(),
        thread.state(),
        thread.reason(),
        revision,
        thread.upgradeSnapshot());
  }

  public void initializeIfNeeded() {
    if (threadsInitialized
        || !host.persistentStateValid()
        || host.hasPaidRuns()
        || host.hasStartingThreads()
        || configurationRevision == Long.MAX_VALUE) {
      return;
    }
    var definition = StructureService.definition(host.machineId());
    if (definition != null && configurationAllowed()) {
      installConfiguration(new FactoryScheduler(definition.factoryPreset().instantiate(host.machineId())));
    }
  }

  public ConfigurationResult resetPreset() {
    if (!configurationAllowed()) {
      return ConfigurationResult.UNAVAILABLE;
    }
    if (host.hasPaidRuns() || host.hasStartingThreads()) {
      return ConfigurationResult.BUSY;
    }
    var definition = StructureService.definition(host.machineId());
    if (definition == null) {
      return ConfigurationResult.UNKNOWN_DEFINITION;
    }
    installConfiguration(new FactoryScheduler(definition.factoryPreset().instantiate(host.machineId())));

    return ConfigurationResult.SUCCESS;
  }

  public ConfigurationResult configureMachine(ResourceLocation machine) {
    if (!host.acceptsMachine(machine)) {
      return ConfigurationResult.INVALID;
    }
    if (!configurationAllowed()) {
      return ConfigurationResult.UNAVAILABLE;
    }
    if (host.hasPaidRuns() || host.hasStartingThreads()) {
      return ConfigurationResult.BUSY;
    }
    var definition = machine == null ? null : StructureService.definition(machine);
    if (definition == null) {
      return ConfigurationResult.UNKNOWN_DEFINITION;
    }
    var replacement = new FactoryScheduler(definition.factoryPreset().instantiate(machine));
    host.bindMachine(machine);
    installConfiguration(replacement);

    return ConfigurationResult.SUCCESS;
  }

  public ConfigurationResult addThread(String id, int priority) {
    if (!configurationAllowed()) {
      return ConfigurationResult.UNAVAILABLE;
    }
    if (host.hasStartingThreads()) {
      return ConfigurationResult.BUSY;
    }
    if (scheduler.channels().size() >= host.maximumThreads()) {
      return ConfigurationResult.LIMIT_REACHED;
    }
    if (scheduler.channel(id) != null) {
      return ConfigurationResult.INVALID;
    }
    try {
      var threads = new ArrayList<>(scheduler.channels());
      threads.add(new FactoryThreadPreset.Thread(id, null, true, priority, 1).instantiate(host.machineId()));
      installConfiguration(new FactoryScheduler(threads));

      return ConfigurationResult.SUCCESS;
    } catch (IllegalArgumentException invalid) {
      return ConfigurationResult.INVALID;
    }
  }

  public ConfigurationResult removeThread(String id) {
    if (!configurationAllowed()) {
      return ConfigurationResult.UNAVAILABLE;
    }
    if (scheduler.channel(id) == null) {
      return ConfigurationResult.THREAD_NOT_FOUND;
    }
    if (host.hasPaidRuns(id) || host.hasStartingThreads()) {
      return ConfigurationResult.BUSY;
    }
    installConfiguration(
        new FactoryScheduler(scheduler.channels().stream().filter(thread -> !thread.threadId().equals(id)).toList()));

    return ConfigurationResult.SUCCESS;
  }

  public ConfigurationResult setThreadPriority(String id, int priority) {
    if (!configurationAllowed()) {
      return ConfigurationResult.UNAVAILABLE;
    }
    var current = scheduler.channel(id);
    if (current == null) {
      return ConfigurationResult.THREAD_NOT_FOUND;
    }
    try {
      var next = current.withPriority(priority);
      if (next == current) {
        return ConfigurationResult.NO_CHANGE;
      }
      scheduler = scheduler.replace(next);
      configurationRevision++;
      host.markChanged();

      return ConfigurationResult.SUCCESS;
    } catch (IllegalArgumentException | ArithmeticException invalid) {
      return ConfigurationResult.INVALID;
    }
  }

  public boolean update(String threadId, long expectedRevision, Change change) {
    Objects.requireNonNull(change, "change");
    boolean changeLock = change.changeLock();
    ResourceLocation lockedRecipe = change.lockedRecipe();
    Boolean enabled = change.enabled();
    Integer parallelLimit = change.parallelLimit();
    Long upgradeRevision = change.upgradeRevision();
    host.requireUpdateThread();
    if (!configurationAllowed() || threadId == null || expectedRevision < 0) {
      return false;
    }
    var current = scheduler.channel(threadId);
    if (current == null || current.revision() != expectedRevision) {
      return false;
    }
    if (changeLock && host.threadBusy(threadId)) {
      return false;
    }
    if (changeLock && lockedRecipe != null && !host.recipeBelongsTo(current.machineId(), lockedRecipe)) {
      return false;
    }
    var next = current;
    if (changeLock) {
      next = next.withLockedRecipe(lockedRecipe);
    }
    if (enabled != null) {
      next = next.withEnabled(enabled);
    }
    if (parallelLimit != null || upgradeRevision != null) {
      if (parallelLimit == null || upgradeRevision == null) {
        return false;
      }
      next = next.withParallelLimit(parallelLimit, upgradeRevision);
    }
    if (next == current) {
      return true;
    }
    scheduler = scheduler.replace(next);
    configurationRevision++;
    host.threadChanged(threadId);
    host.markChanged();
    return true;
  }

}
