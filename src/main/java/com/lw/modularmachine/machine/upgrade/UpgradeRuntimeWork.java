package com.lw.modularmachine.machine.upgrade;

import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.api.upgrade.UpgradeRuntimeData;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.recipe.FrozenRecipeRun;
import com.lw.modularmachine.machine.recipe.RecipePreparationHandlers;
import com.lw.modularmachine.machine.recipe.RecipeTickPreparation;
import com.lw.modularmachine.machine.recipe.PatternRunSources;
import com.lw.modularmachine.api.recipe.RecipePreparationEvents;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;

/**
 * One owner budget for all runtime phases; one resumable proposal per phase and
 * logical thread.
 */
@ApiStatus.Internal
public final class UpgradeRuntimeWork {
  private record Key(UpgradeRuntimeData.Phase phase, String threadId) {
  }

  private final UpgradeBusOwner owner;
  private final Map<Key, UpgradeTickCosts> jobs = new HashMap<>();
  private record RecipeKey(String threadId) {
  }
  private final Map<RecipeKey, RecipeTickPreparation> recipeJobs = new HashMap<>();
  private final ArrayDeque<Object> pending = new ArrayDeque<>();
  private long budgetTick = Long.MIN_VALUE;
  private long epoch = Long.MIN_VALUE;
  private int budgetLeft;

  public UpgradeRuntimeWork(UpgradeBusOwner owner) {
    this.owner = owner;
  }

  public UpgradeTickCosts prepare(String threadId, List<FrozenRecipeRun> runs) {
    return prepare(UpgradeRuntimeData.Phase.TICK, threadId, runs);
  }

  public UpgradeTickCosts prepare(UpgradeRuntimeData.Phase phase, String threadId, List<FrozenRecipeRun> runs) {
    if (!(owner.getLevel() instanceof ServerLevel level)) {
      throw new IllegalStateException("Runtime upgrade work requires a server level");
    }
    ServerThreadGuard.require(level, "Runtime upgrade work");
    if (UpgradeLifecyclePublisher.isDispatchingAny()) {
      return UpgradeTickCosts.prepare(owner, phase, threadId, runs);
    }
    beginBudget(level);
    var definitions = UpgradeCatalogService.snapshot();
    if (!definitions.hasData(phase)) {
      return UpgradeTickCosts.prepare(owner, phase, threadId, runs);
    }
    var key = new Key(phase, threadId);
    var job = jobs.get(key);
    if (job == null || !job.reusable(phase, threadId, runs)) {
      if (job != null) {
        job.invalidate();
      }
      job = UpgradeTickCosts.prepare(owner, phase, threadId, runs);
      if (job.status() == UpgradeTickCosts.Status.INVALID) {
        jobs.remove(key);
        pending.remove(key);
        return job;
      }
      jobs.put(key, job);
      pending.remove(key);
      if (job.status() == UpgradeTickCosts.Status.PENDING) {
        pending.addLast(key);
      }
    }
    job.authorizeTick();
    advanceNext();

    return job;
  }

  private void clearJobs() {
    jobs.values().forEach(UpgradeTickCosts::invalidate);
    jobs.clear();
    recipeJobs.clear();
    pending.clear();
  }

  public RecipeTickPreparation.Status prepareRecipeTick(String threadId, List<FrozenRecipeRun> runs) {
    var level = (ServerLevel) owner.getLevel();
    ServerThreadGuard.require(level, "Recipe pre-tick work");
    if (runs.stream().noneMatch(
        run -> RecipePreparationHandlers
            .has(owner.machineId(), run.recipeId(), RecipePreparationEvents.Type.PRE_TICK))) {
      return RecipeTickPreparation.Status.ALLOWED;
    }
    var registration = owner.structureRegistration();
    if (registration == null
        || !owner.canCommitStructure()
        || runs.stream()
            .anyMatch(run -> !run.matchesBindings(registration.pattern) || !PatternRunSources.available(owner, run))) {
      return RecipeTickPreparation.Status.STALE;
    }
    beginBudget(level);
    var key = new RecipeKey(threadId);
    var job = recipeJobs.get(key);
    if (job == null || !job.reusable(runs)) {
      job = new RecipeTickPreparation(owner, threadId, runs);
      recipeJobs.put(key, job);
      pending.remove(key);
      pending.addLast(key);
    }
    advanceNext();
    return job.status();
  }

  /** A pre-tick gate must also let an older upgrade proposal leave the queue. */
  private void advanceNext() {
    if (pending.isEmpty() || budgetLeft <= 0) {
      return;
    }
    int allowance = Math.min(budgetLeft, Math.max(1, MachineConfig.RECIPE_OUTPUT_WORK.get() / pending.size()));
    var key = pending.removeFirst();
    boolean incomplete = false;
    if (key instanceof Key upgradeKey) {
      var job = jobs.get(upgradeKey);
      if (job != null && job.runsCurrent()) {
        job.authorizeTick();
        budgetLeft -= job.advanceData(allowance);
        incomplete = job.status() == UpgradeTickCosts.Status.PENDING;
      }
    } else if (key instanceof RecipeKey recipeKey) {
      var job = recipeJobs.get(recipeKey);
      if (job != null && job.current()) {
        budgetLeft -= job.advance(allowance);
        incomplete = job.status() == RecipeTickPreparation.Status.PENDING;
      }
    }
    if (incomplete) {
      pending.addLast(key);
    }
  }

  private void beginBudget(ServerLevel level) {
    var definitions = UpgradeCatalogService.snapshot();
    if (definitions.epoch() != epoch) {
      clearJobs();
      epoch = definitions.epoch();
    }
    if (budgetTick == level.getGameTime()) {
      return;
    }
    budgetTick = level.getGameTime();
    budgetLeft = MachineConfig.RECIPE_OUTPUT_WORK.get();
    var iterator = jobs.entrySet().iterator();
    while (iterator.hasNext()) {
      var entry = iterator.next();
      if (!entry.getValue().runsCurrent()) {
        entry.getValue().invalidate();
        pending.remove(entry.getKey());
        iterator.remove();
      }
    }
    var recipes = recipeJobs.entrySet().iterator();
    while (recipes.hasNext()) {
      var entry = recipes.next();
      if (!entry.getValue().current()) {
        pending.remove(entry.getKey());
        recipes.remove();
      }
    }
  }

  public void clear(String threadId) {
    requireMutationThread();
    for (var phase : UpgradeRuntimeData.Phase.values()) {
      clear(phase, threadId);
    }
  }

  public void clear(UpgradeRuntimeData.Phase phase, String threadId) {
    requireMutationThread();
    var key = new Key(phase, threadId);
    var job = jobs.remove(key);
    if (job != null) {
      job.invalidate();
    }
    pending.remove(key);
    if (phase == UpgradeRuntimeData.Phase.TICK) {
      var recipeKey = new RecipeKey(threadId);
      recipeJobs.remove(recipeKey);
      pending.remove(recipeKey);
    }
  }

  public void clear() {
    requireMutationThread();
    clearJobs();
  }

  private void requireMutationThread() {
    if (owner.getLevel() instanceof ServerLevel level) {
      ServerThreadGuard.require(level, "Runtime upgrade cache mutation");
    }
  }
}
