package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.recipe.FrozenItemRun;
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
public final class FactoryUpgradeRuntimeWork {
  private record Key(FactoryUpgradeRuntimeData.Phase phase, String threadId) {
  }

  private final UpgradeBusOwner owner;
  private final Map<Key, FactoryUpgradeTickCosts> jobs = new HashMap<>();
  private final ArrayDeque<Key> pending = new ArrayDeque<>();
  private long budgetTick = Long.MIN_VALUE;
  private long epoch = Long.MIN_VALUE;
  private int budgetLeft;

  public FactoryUpgradeRuntimeWork(UpgradeBusOwner owner) {
    this.owner = owner;
  }

  public FactoryUpgradeTickCosts prepare(String threadId, List<FrozenItemRun> runs) {
    return prepare(FactoryUpgradeRuntimeData.Phase.TICK, threadId, runs);
  }

  public FactoryUpgradeTickCosts prepare(FactoryUpgradeRuntimeData.Phase phase, String threadId, List<FrozenItemRun> runs) {
    if (!(owner.getLevel() instanceof ServerLevel level)) {
      throw new IllegalStateException("Runtime upgrade work requires a server level");
    }
    ServerThreadGuard.require(level, "Runtime upgrade work");
    if (FactoryUpgradeEvents.isDispatchingAny()) {
      return FactoryUpgradeTickCosts.prepare(owner, phase, threadId, runs);
    }
    var definitions = FactoryUpgradeService.snapshot();
    if (definitions.epoch() != epoch) {
      clearJobs();
      epoch = definitions.epoch();
    }
    if (budgetTick != level.getGameTime()) {
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
    }
    if (!definitions.hasData(phase)) {
      return FactoryUpgradeTickCosts.prepare(owner, phase, threadId, runs);
    }
    var key = new Key(phase, threadId);
    var job = jobs.get(key);
    if (job == null || !job.reusable(phase, threadId, runs)) {
      if (job != null) {
        job.invalidate();
      }
      job = FactoryUpgradeTickCosts.prepare(owner, phase, threadId, runs);
      if (job.status() == FactoryUpgradeTickCosts.Status.INVALID) {
        jobs.remove(key);
        pending.remove(key);
        return job;
      }
      jobs.put(key, job);
      pending.remove(key);
      if (job.status() == FactoryUpgradeTickCosts.Status.PENDING) {
        pending.addLast(key);
      }
    }
    job.authorizeTick();
    if (job.status() == FactoryUpgradeTickCosts.Status.PENDING && budgetLeft > 0 && key.equals(pending.peekFirst())) {
      int allowance = Math.min(budgetLeft, Math.max(1, MachineConfig.RECIPE_OUTPUT_WORK.get() / pending.size()));
      budgetLeft -= job.advanceData(allowance);
      pending.remove(key);
      if (job.status() == FactoryUpgradeTickCosts.Status.PENDING) {
        pending.addLast(key);
      }
    }

    return job;
  }

  private void clearJobs() {
    jobs.values().forEach(FactoryUpgradeTickCosts::invalidate);
    jobs.clear();
    pending.clear();
  }

  public void clear(String threadId) {
    requireMutationThread();
    for (var phase : FactoryUpgradeRuntimeData.Phase.values()) {
      clear(phase, threadId);
    }
  }

  public void clear(FactoryUpgradeRuntimeData.Phase phase, String threadId) {
    requireMutationThread();
    var key = new Key(phase, threadId);
    var job = jobs.remove(key);
    if (job != null) {
      job.invalidate();
    }
    pending.remove(key);
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
