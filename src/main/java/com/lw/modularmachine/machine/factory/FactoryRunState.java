package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.machine.upgrade.UpgradeLifecyclePublisher;
import com.lw.modularmachine.api.recipe.RecipeExecutionOptions;
import com.lw.modularmachine.machine.recipe.FrozenRecipeRun;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.ApiStatus;

/** Authoritative paid batches and in-flight start admission for one factory. */
@ApiStatus.Internal
public final class FactoryRunState {
  private final FactoryThreadConfiguration threadConfiguration;
  private final Host host;
  private final Map<String, FactoryRunSet> runSets = new HashMap<>();
  private final Map<String, FactoryRunSet> view = Collections.unmodifiableMap(runSets);
  private final Set<String> startingThreads = new HashSet<>();
  private final Set<String> startingView = Collections.unmodifiableSet(startingThreads);
  private final Map<String, Integer> parallelAdmissionLimits = new HashMap<>();

  public interface Host {
    void requireThread();
    void requireLoadAllowed();
    void refreshUpgrades();
    boolean validSources();
    boolean startAllowed(String threadId);
    boolean upgradesMatch(FactoryThreadState thread);
    int parallelLimit(String threadId);
    boolean recipeAllowed(String threadId, ResourceLocation recipeId, RecipeExecutionOptions options);
  }

  public FactoryRunState(FactoryThreadConfiguration threadConfiguration, Host host) {
    this.threadConfiguration = java.util.Objects.requireNonNull(threadConfiguration, "threadConfiguration");
    this.host = java.util.Objects.requireNonNull(host, "host");
  }

  /**
   * Main-thread view retaining the original iteration order; entries are
   * immutable batches.
   */
  public Map<String, FactoryRunSet> view() {
    return view;
  }

  public Set<String> startingThreads() {
    return startingView;
  }

  public FactoryRunSet runSet(String threadId) {
    return runSets.getOrDefault(threadId, FactoryRunSet.empty());
  }

  public void clearRuns() {
    runSets.clear();
  }

  public void clearStarts() {
    startingThreads.clear();
    parallelAdmissionLimits.clear();
  }

  public boolean beginRunStart(String threadId) {
    if (UpgradeLifecyclePublisher.isDispatchingAny()) {
      return false;
    }
    host.refreshUpgrades();
    var thread = threadConfiguration.scheduler().channel(threadId);
    if (!host.validSources()
        || thread == null
        || !host.startAllowed(threadId)
        || !host.upgradesMatch(thread)
        || !runSet(threadId).runs().isEmpty()
        || !startingThreads.add(threadId)) {
      return false;
    }
    try {
      parallelAdmissionLimits.put(threadId, host.parallelLimit(threadId));
    } catch (RuntimeException unavailable) {
      startingThreads.remove(threadId);
      parallelAdmissionLimits.remove(threadId);
      return false;
    }
    return true;
  }

  public void installRun(String threadId, FrozenRecipeRun run) {
    installRuns(threadId, List.of(run));
  }

  public void installRuns(String threadId, List<FrozenRecipeRun> runs) {
    if (!startingThreads.contains(threadId) || !host.startAllowed(threadId) || runs == null || runs.isEmpty()) {
      throw new IllegalStateException("Invalid factory run installation");
    }
    if (runs.stream().anyMatch(run -> !host.recipeAllowed(threadId, run.recipeId(), run.executionOptions()))) {
      throw new IllegalStateException("Recipe factory thread admission changed before installation");
    }
    var thread = threadConfiguration.scheduler().channels().stream()
        .filter(candidate -> candidate.threadId().equals(threadId)).findFirst().orElseThrow();
    if (runs.size() > parallelAdmissionLimits.getOrDefault(threadId, thread.parallelLimit())) {
      throw new IllegalArgumentException("Batch exceeds current factory thread parallel limit");
    }
    runSets.put(threadId, runSets.getOrDefault(threadId, FactoryRunSet.empty()).addAll(runs));
  }

  public void finishRunStart(String threadId, boolean committed) {
    startingThreads.remove(threadId);
    parallelAdmissionLimits.remove(threadId);
  }

  public boolean removeRunIfSame(String threadId, FrozenRecipeRun expected) {
    host.requireThread();
    if (UpgradeLifecyclePublisher.isDispatchingAny()) {
      return false;
    }
    var set = runSets.get(threadId);
    if (set == null || set.find(expected.runId()).orElse(null) != expected) {
      return false;
    }
    runSets.put(threadId, set.remove(expected.runId()));
    if (runSets.get(threadId).runs().isEmpty()) {
      runSets.remove(threadId);
    }
    return true;
  }

  public boolean replaceRunIfSame(String threadId, FrozenRecipeRun expected, FrozenRecipeRun replacement) {
    host.requireThread();
    if (UpgradeLifecyclePublisher.isDispatchingAny()) {
      return false;
    }
    var set = runSets.get(threadId);
    if (set == null || replacement == null || set.find(expected.runId()).orElse(null) != expected) {
      return false;
    }
    runSets.put(threadId, set.replace(replacement));
    return true;
  }

  public boolean removeRunSetIfSame(String threadId, FactoryRunSet expected) {
    host.requireThread();
    if (UpgradeLifecyclePublisher.isDispatchingAny()) {
      return false;
    }
    if (runSets.get(threadId) != expected) {
      return false;
    }
    runSets.remove(threadId);
    return true;
  }

  public boolean replaceRunSetIfSame(String threadId, FactoryRunSet expected, FactoryRunSet replacement) {
    host.requireThread();
    if (UpgradeLifecyclePublisher.isDispatchingAny()) {
      return false;
    }
    if (runSets.get(threadId) != expected || replacement == null) {
      return false;
    }
    runSets.put(threadId, replacement);
    return true;
  }

  public boolean applyRunFailureDecisionsIfSame(String threadId, FactoryRunSet expected, Map<UUID, FrozenRecipeRun> replacements, Set<UUID> discarded) {
    host.requireThread();
    if (UpgradeLifecyclePublisher.isDispatchingAny() || runSets.get(threadId) != expected) {
      return false;
    }
    FactoryRunSet replacement = expected.applyFailureDecisions(replacements, discarded);
    if (replacement == expected) {
      return false;
    }
    if (replacement.runs().isEmpty()) {
      runSets.remove(threadId);
    } else {
      runSets.put(threadId, replacement);
    }

    return true;
  }

  public void save(CompoundTag tag) {
    var savedRuns = new ListTag();
    for (var entry : runSets.entrySet()) {
      var saved = new CompoundTag();
      saved.putString("thread", entry.getKey());
      saved.put("set", FactoryRunSetCodec.write(entry.getValue()));
      savedRuns.add(saved);
    }
    tag.put("runs", savedRuns);
  }

  public void load(CompoundTag tag, HolderLookup.Provider registries) {
    host.requireLoadAllowed();
    var loaded = threadConfiguration.scheduler().channels();
    if (!(tag.get("runs") instanceof ListTag runsTag)
        || !runsTag.isEmpty() && runsTag.getElementType() != Tag.TAG_COMPOUND) {
      throw new IllegalArgumentException("Invalid factory run list type");
    }
    ListTag savedRuns = runsTag;
    if (savedRuns.size() > FactoryThreadStateCodec.MAX_THREADS) {
      throw new IllegalArgumentException("Too many factory thread run entries");
    }
    if (!threadConfiguration.initialized() && (!loaded.isEmpty() || !savedRuns.isEmpty())) {
      throw new IllegalArgumentException("Uninitialized factory contains threads or paid runs");
    }
    for (int i = 0; i < savedRuns.size(); i++) {
      var saved = savedRuns.getCompound(i);
      if (!saved.contains("thread", Tag.TAG_STRING) || !saved.contains("set", Tag.TAG_LIST)) {
        throw new IllegalArgumentException("Invalid factory run entry");
      }
      String threadId = saved.getString("thread");
      var runSet = FactoryRunSetCodec.read(saved.getList("set", Tag.TAG_COMPOUND), registries);
      if (runSets.put(threadId, runSet) != null) {
        throw new IllegalArgumentException("Duplicate factory run thread");
      }
      if (runSet.runs().isEmpty()) {
        throw new IllegalArgumentException("Empty factory run set entry");
      }
      var thread = threadConfiguration.scheduler().channels().stream()
          .filter(candidate -> candidate.threadId().equals(threadId)).findFirst()
          .orElseThrow(() -> new IllegalArgumentException("Run refers to missing factory thread"));
      threadConfiguration.replaceRuntime(
          threadConfiguration.scheduler()
              .replace(thread.withState(FactoryThreadState.State.RUNNING, FactoryThreadState.Reason.NONE)));
    }
  }
}
