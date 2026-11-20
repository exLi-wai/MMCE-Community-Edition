package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.machine.recipe.FactoryRunResourceResult;
import com.lw.modularmachine.machine.upgrade.UpgradeCatalogService;
import com.lw.modularmachine.machine.recipe.FactoryOutputEngine;
import com.lw.modularmachine.machine.recipe.FrozenRecipeRun;
import com.lw.modularmachine.machine.recipe.RecipeLifecyclePublisher;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecutionRegistry;
import com.lw.modularmachine.api.recipe.RecipeLifecycleEvents.Type;
import net.minecraft.core.HolderLookup;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import org.jetbrains.annotations.ApiStatus;
import java.util.Objects;

/**
 * Advances the factory's paid batches before new searches, sharing existing
 * resource committers.
 */
@ApiStatus.Internal
public final class FactoryRunCoordinator {
  private final FactoryThreadConfiguration threadConfiguration;
  private final FactoryRunState runState;
  private final Host host;
  private final FactoryOutputEngine outputEngine = new FactoryOutputEngine();

  public interface Host {
    boolean confirmed();
    HolderLookup.Provider registries();
    long gameTime();
    boolean bindingsCurrent(FrozenRecipeRun run);
    default boolean beforeTick(String threadId, List<FrozenRecipeRun> runs) {
      return true;
    }
    FactoryRunResourceResult tickParallel(String threadId, FactoryRunSet set);
    FactoryRunResourceResult tickSingle(String threadId, FrozenRecipeRun run);
    FactoryOutputEngine.Result advanceOutput(FactoryOutputEngine engine, String threadId, FactoryRunSet set);
    void advanced(String threadId, List<FrozenRecipeRun> before, List<FrozenRecipeRun> after);
    void markChanged();
    void updateThreadState(FactoryThreadState previous, FactoryThreadState.State state, FactoryThreadState.Reason reason);
  }

  public FactoryRunCoordinator(FactoryThreadConfiguration threads, FactoryRunState runs, Host host) {
    threadConfiguration = Objects.requireNonNull(threads, "threads");
    runState = Objects.requireNonNull(runs, "runs");
    this.host = Objects.requireNonNull(host, "host");
  }

  /**
   * Keep per-tick attempt fences across same-instance reload and topology
   * changes.
   */
  public void invalidateOutput() {
    outputEngine.invalidate();
  }

  public void unloadOutput() {
    outputEngine.clear();
  }

  public FactoryOutputEngine.Result advanceOutput(String threadId, FactoryRunSet set) {
    return host.advanceOutput(outputEngine, threadId, set);
  }

  public void advance() {
    if (!host.confirmed()) {
      outputEngine.invalidate();
      return;
    }
    for (var entry : List.copyOf(runState.view().entrySet())) {
      advanceThread(entry);
    }
  }

  private void advanceThread(Map.Entry<String, FactoryRunSet> entry) {
    String threadId = entry.getKey();
    var thread = threadConfiguration.scheduler().channel(threadId);
    if (thread == null || !thread.enabled()) {
      return;
    }
    if (entry.getValue().runs().stream().allMatch(FrozenRecipeRun::pendingOutput)) {
      advancePendingOutput(threadId, entry.getValue());
      return;
    }
    var active = entry.getValue().runs().stream()
        .filter(run -> !run.pendingOutput() && run.lastProgressTick() < host.gameTime()).toList();
    if (!active.isEmpty() && !host.beforeTick(threadId, active)) {
      host.updateThreadState(thread, FactoryThreadState.State.WAITING, FactoryThreadState.Reason.NONE);
      return;
    }
    if (entry.getValue().runs().size() > 1
        && entry.getValue().runs().stream().anyMatch(
            run -> run.energyPerTick() > 0
                || run.energyOutputPerTick() > 0
                || !run.fluidPerTick(host.registries()).isEmpty()
                || RecipeResourceExecutionRegistry.hasTickResources(run, host.registries()))
        || UpgradeCatalogService.snapshot().hasTickWork()) {
      var result = host.tickParallel(threadId, entry.getValue());
      var status = FactoryRunStatus.from(result);
      host.updateThreadState(thread, status.state(), status.reason());
      if (result == FactoryRunResourceResult.ADVANCED) {
        advancePendingOutput(threadId, runState.runSet(threadId));
      }
      return;
    }
    advanceRuns(threadId, thread, entry);
  }

  private void advanceRuns(String threadId, FactoryThreadState thread, Map.Entry<String, FactoryRunSet> entry) {
    FactoryThreadState.State threadState = FactoryThreadState.State.RUNNING;
    FactoryThreadState.Reason threadReason = FactoryThreadState.Reason.NONE;
    var replacements = new HashMap<UUID, FrozenRecipeRun>();
    for (var run : entry.getValue().runs()) {
      if (run.pendingOutput()) {
        continue;
      }
      if (run.energyPerTick() > 0
          || run.energyOutputPerTick() > 0
          || !run.fluidPerTick(host.registries()).isEmpty()
          || RecipeResourceExecutionRegistry.hasTickResources(run, host.registries())) {
        var result = host.tickSingle(threadId, run);
        if (result != FactoryRunResourceResult.ADVANCED) {
          var status = FactoryRunStatus.from(result);
          threadState = status.state();
          threadReason = status.reason();
        }
        continue;
      }
      if (!host.bindingsCurrent(run)) {
        threadState = FactoryThreadState.State.WAITING;
        threadReason = FactoryThreadState.Reason.STALE_RESOURCES;
        break;
      }
      var advanced = run.advanced(host.gameTime());
      if (advanced == run) {
        continue;
      }
      if (advanced.progress() == advanced.duration()) {
        advanced = advanced.finished();
        threadState = FactoryThreadState.State.WAITING;
        threadReason = FactoryThreadState.Reason.OUTPUT_PLANNING;
      }
      replacements.put(run.runId(), advanced);
    }
    if (threadReason == FactoryThreadState.Reason.STALE_RESOURCES) {
      // All free-run replacements are still private; reject the whole batch.
      host.updateThreadState(thread, threadState, threadReason);
      return;
    }
    if (!replacements.isEmpty()) {
      var updated = entry.getValue().replaceAll(replacements);
      if (runState.replaceRunSetIfSame(threadId, entry.getValue(), updated)) {
        host.markChanged();
        if (RecipeLifecyclePublisher.hasListeners(Type.TICK)) {
          var before = entry.getValue().runs().stream().filter(run -> replacements.containsKey(run.runId())).toList();
          var after = before.stream().map(run -> replacements.get(run.runId())).toList();
          host.advanced(threadId, before, after);
        }
      } else {
        threadState = FactoryThreadState.State.WAITING;
        threadReason = FactoryThreadState.Reason.STALE_RESOURCES;
      }
    }
    if (runState.runSet(threadId).runs().isEmpty()) {
      threadState = FactoryThreadState.State.IDLE;
      threadReason = FactoryThreadState.Reason.NONE;
    }
    var currentSet = runState.runSet(threadId);
    if (!currentSet.runs().isEmpty() && currentSet.runs().stream().allMatch(FrozenRecipeRun::pendingOutput)) {
      advancePendingOutput(threadId, currentSet);
      return;
    }
    var current = threadConfiguration.scheduler().channel(threadId);
    host.updateThreadState(current == null ? thread : current, threadState, threadReason);
  }

  private void advancePendingOutput(String threadId, FactoryRunSet completed) {
    var thread = threadConfiguration.scheduler().channel(threadId);
    if (thread == null
        || completed == null
        || completed.runs().isEmpty()
        || completed.runs().stream().anyMatch(run -> !run.pendingOutput())) {
      return;
    }
    var result = advanceOutput(threadId, completed);
    switch (result) {
      case PLANNING ->
        host.updateThreadState(thread, FactoryThreadState.State.WAITING, FactoryThreadState.Reason.OUTPUT_PLANNING);
      case BLOCKED ->
        host.updateThreadState(thread, FactoryThreadState.State.BLOCKED, FactoryThreadState.Reason.OUTPUT_BLOCKED);
      case STALE ->
        host.updateThreadState(thread, FactoryThreadState.State.WAITING, FactoryThreadState.Reason.STALE_RESOURCES);
      case COMMITTED -> host.updateThreadState(thread, FactoryThreadState.State.IDLE, FactoryThreadState.Reason.NONE);
      case UPGRADE_PENDING -> host
          .updateThreadState(thread, FactoryThreadState.State.WAITING, FactoryThreadState.Reason.UPGRADE_DATA_PENDING);
      case WAITING_UPGRADE -> host
          .updateThreadState(thread, FactoryThreadState.State.WAITING, FactoryThreadState.Reason.WAITING_UPGRADE_COST);
      case UPGRADE_ERROR ->
        host.updateThreadState(thread, FactoryThreadState.State.WAITING, FactoryThreadState.Reason.UPGRADE_ERROR);
    }
  }

}
