package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.machine.upgrade.UpgradeRecipeCheckHandlers;
import com.lw.modularmachine.machine.upgrade.UpgradeCatalogService;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.RecipeInputMiss;
import com.lw.modularmachine.machine.recipe.ControllerMetrics;
import com.lw.modularmachine.machine.recipe.RecipeStartPlan;
import com.lw.modularmachine.machine.recipe.FactoryRecipeStarter;
import com.lw.modularmachine.machine.recipe.FactoryParallelStarter;
import com.lw.modularmachine.machine.recipe.search.RecipeCatalog;
import com.lw.modularmachine.machine.recipe.search.RecipeSearch;
import com.lw.modularmachine.machine.recipe.search.RecipeResourceRequirements;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.ApiStatus;
import java.util.Objects;

/**
 * Owns bounded factory searches and retry state; paid runs and scheduler stay
 * with their owners.
 */
@ApiStatus.Internal
public final class FactorySearchCoordinator {
  private static final int SEARCH_RETRY_INITIAL = 5;
  private static final int SEARCH_RETRY_MAX = 80;
  private final FactoryThreadConfiguration threadConfiguration;
  private final Host host;
  private record Attempt(RecipeSearch search, long inputRevision) {
  }
  private final Map<String, Attempt> searches = new HashMap<>();
  private final Map<String, RecipeSearch.LockedRestart> lockedRestarts = new HashMap<>();
  private final Map<String, RecipeInputMiss> missingInputs = new HashMap<>();
  private final Retries retries = new Retries();
  private long wakeRevision;
  private long inputRevision;
  private long checkEpoch = Long.MIN_VALUE;
  private final Map<String, String> interfaceFailures = new HashMap<>();

  public interface Host {
    boolean available();
    long gameTime();
    boolean hasRuns(String threadId);
    boolean startAllowed(String threadId);
    RecipeSearch beginSearch(String threadId, RecipeCatalog catalog, Set<String> allowed, RecipeSearch.LockedRestart restart);
    int parallelLimit(String threadId);
    int maximumUpgradeRuns();
    MachineRecipe definitionForStart(RecipeStartPlan plan);
    FactoryRecipeStarter.Result startSingle(String threadId, RecipeStartPlan plan, int workBudget);
    FactoryParallelStarter.Result startParallel(String threadId, RecipeStartPlan plan, int count, int workBudget);
  }

  public FactorySearchCoordinator(FactoryThreadConfiguration threadConfiguration, Host host) {
    this.threadConfiguration = Objects.requireNonNull(threadConfiguration, "threadConfiguration");
    this.host = Objects.requireNonNull(host, "host");
  }

  public void advance(RecipeCatalog catalog, ControllerMetrics metrics, long serverTick) {
    if (!host.available() || catalog == null || catalog.recipes().size() == 0) {
      invalidate();
      return;
    }
    long epoch = UpgradeCatalogService.snapshot().epoch();
    if (checkEpoch != epoch) {
      wakeRetries();
      checkEpoch = epoch;
    }
    var channels = threadConfiguration.scheduler().channels();
    if (channels.isEmpty()) {
      return;
    }
    int visitBudget = Math.min(MachineConfig.FACTORY_THREADS_PER_TICK.get(), channels.size());
    long now = host.gameTime();
    long deadline = RecipeSearch.tickDeadline(System.nanoTime());
    threadConfiguration.visitWindow(visitBudget, visited -> {
      advanceThread(visited.threadId(), catalog, now, metrics, serverTick, deadline);
      return System.nanoTime() - deadline < 0;
    });
  }

  private void advanceThread(String threadId, RecipeCatalog catalog, long now, ControllerMetrics metrics, long serverTick, long deadline) {
    var thread = threadConfiguration.scheduler().channel(threadId);
    if (thread == null
        || !thread.enabled()
        || host.hasRuns(thread.threadId())
        || thread.state() == FactoryThreadState.State.INVALID) {
      return;
    }
    if (!host.startAllowed(thread.threadId())) {
      invalidateThread(thread.threadId(), now);
      threadConfiguration.updateThreadState(
          thread.threadId(),
          FactoryThreadState.State.WAITING,
          FactoryThreadState.Reason.THREAD_LIMIT);
      return;
    }
    var attempt = searches.get(thread.threadId());
    var search = attempt == null ? null : attempt.search();
    if (search == null && retries.waiting(thread.threadId(), now)) {
      return;
    }
    long started = System.nanoTime();
    if (search != null
        && (search.buildingInputMiss() && attempt.inputRevision() != inputRevision || !search.dependenciesCurrent())) {
      invalidateThread(thread.threadId(), now);
      search = null;
    }
    if (search == null) {
      interfaceFailures.remove(thread.threadId());
      var allowed = thread.lockedRecipe() == null ? null : Set.of(thread.lockedRecipe().toString());
      long capturedInputRevision = inputRevision;
      try {
        var miss = missingInputs.get(threadId);
        if (miss != null) {
          long revision = wakeRevision;
          var status = miss.advance(catalog, now, MachineConfig.FACTORY_SEARCH_WORK.get(), deadline);
          if (missingInputs.get(threadId) == miss) {
            if (status == RecipeInputMiss.Status.STALE) {
              missingInputs.remove(threadId);
            } else if (status == RecipeInputMiss.Status.UNCHANGED && revision == wakeRevision) {
              retries.deferVerified(threadId, miss.nextCheck());
            }
          }
          // A stale or reentrantly invalidated proof starts a fresh search on the next
          // visit.
          metrics.recordSearch(System.nanoTime() - started, serverTick);
          return;
        }
        missingInputs.remove(threadId);
        search = host.beginSearch(thread.threadId(), catalog, allowed, lockedRestarts.remove(thread.threadId()));
      } catch (RuntimeException failure) {
        missingInputs.remove(threadId);
        metrics.recordSearch(System.nanoTime() - started, serverTick);
        if (!(failure instanceof UpgradeRecipeCheckHandlers.FailedException)) {
          throw failure;
        }
        threadConfiguration.updateThreadState(
            thread.threadId(),
            FactoryThreadState.State.WAITING,
            FactoryThreadState.Reason.CHECK_ERROR);
        backoffSearch(thread.threadId(), now);
        recordSearchAttempt(thread.threadId());
        return;
      }
      if (search == null) {
        metrics.recordSearch(System.nanoTime() - started, serverTick);
        var reason = thread.upgradeSnapshot().recipeModifiers()
            .isEmpty() ? FactoryThreadState.Reason.STALE_RESOURCES : FactoryThreadState.Reason.SEARCH_BUDGET;
        threadConfiguration.updateThreadState(thread.threadId(), FactoryThreadState.State.WAITING, reason);
        backoffSearch(thread.threadId(), now);
        recordSearchAttempt(thread.threadId());
        return;
      }
      attempt = new Attempt(search, capturedInputRevision);
      searches.put(thread.threadId(), attempt);
    }
    try {
      var activeAttempt = attempt;
      int budget = MachineConfig.FACTORY_SEARCH_WORK.get();
      RecipeSearch.Result result;
      try {
        result = search.advance(budget, deadline);
      } finally {
        metrics.recordSearch(System.nanoTime() - started, serverTick);
      }
      switch (result.status()) {
        case SELECTED -> {
          result.plan().ifPresent(plan -> {
            startSelected(thread, activeAttempt, plan, now, budget - result.workUsed());
          });
        }
        case BUDGET_EXHAUSTED -> threadConfiguration.updateThreadState(
            thread.threadId(),
            FactoryThreadState.State.WAITING,
            FactoryThreadState.Reason.SEARCH_BUDGET);
        case NO_MATCH -> {
          if (searches.get(threadId) != activeAttempt) {
            return;
          }
          if (search.missingInputs() != null && activeAttempt.inputRevision() != inputRevision) {
            invalidateThread(threadId, now);
            return;
          }
          String interfaceFailure = search.interfaceFailure();
          var missing = search.missingResource();
          FactoryThreadState.Reason reason;
          if (missing.isPresent()) {
            reason = RecipeResourceRequirements.factoryReason(missing.orElseThrow());
          } else if (thread.lockedRecipe() == null) {
            reason = FactoryThreadState.Reason.NO_RECIPE;
          } else {
            reason = FactoryThreadState.Reason.INPUT_MISSING;
          }
          threadConfiguration.updateThreadState(thread.threadId(), FactoryThreadState.State.WAITING, reason);
          clearThread(thread.threadId());
          if (search.missingInputs() != null) {
            missingInputs.put(threadId, search.missingInputs());
          }
          if (!interfaceFailure.isEmpty()) {
            interfaceFailures.put(thread.threadId(), interfaceFailure);
          }
          backoffSearch(thread.threadId(), now);
        }
        default -> {
        }
      }
    } catch (RuntimeException failure) {
      clearThread(thread.threadId());
      backoffSearch(thread.threadId(), now);
      var reason = failure instanceof UpgradeRecipeCheckHandlers.StaleException ? FactoryThreadState.Reason.STALE_RESOURCES : FactoryThreadState.Reason.CHECK_ERROR;
      threadConfiguration.updateThreadState(thread.threadId(), FactoryThreadState.State.WAITING, reason);
    }
    recordSearchAttempt(thread.threadId());
  }

  private void startSelected(FactoryThreadState thread, Attempt attempt, RecipeStartPlan plan, long now, int workBudget) {
    var activeSearch = attempt.search();
    var nativeRecipe = host.definitionForStart(plan);
    if (nativeRecipe == null) {
      clearThread(thread.threadId());
      backoffSearch(thread.threadId(), now);
      return;
    }
    int threadLimit = host.parallelLimit(thread.threadId());
    int upgradeLimit = host.maximumUpgradeRuns();
    // A single resource check distinguishes missing upgrade costs from missing
    // inputs.
    int probeLimit = Math
        .min(nativeRecipe.parallelized() ? threadLimit : Math.min(threadLimit, 1), Math.max(1, upgradeLimit));
    int resourceLimit = plan.maxRepeatableInputs(Math.min(probeLimit, plan.maxRepeatableOutputs()));
    int upper = Math.min(resourceLimit, upgradeLimit);
    int maxInstances = upper == 0 ? 0 : plan.prepareParallel(nativeRecipe, upper, workBudget);
    if (maxInstances < 0) {
      threadConfiguration.updateThreadState(
          thread.threadId(),
          FactoryThreadState.State.WAITING,
          FactoryThreadState.Reason.SEARCH_BUDGET);
      return;
    }
    var startResult = FactoryRecipeStarter.Result.STALE;
    if (maxInstances == 0 && plan.modifiedOutputsRejected()) {
      startResult = FactoryRecipeStarter.Result.CHECK_REJECTED;
    } else if (maxInstances == 0 && resourceLimit > 0) {
      startResult = FactoryRecipeStarter.Result.UPGRADE_COST_MISSING;
    }
    if (maxInstances > 1) {
      var parallelResult = host.startParallel(thread.threadId(), plan, maxInstances, plan.parallelBudgetRemaining());
      startResult = switch (parallelResult) {
        case STARTED -> FactoryRecipeStarter.Result.STARTED;
        case CHECK_REJECTED -> FactoryRecipeStarter.Result.CHECK_REJECTED;
        case CHECK_PENDING -> FactoryRecipeStarter.Result.CHECK_PENDING;
        case CHECK_ERROR -> FactoryRecipeStarter.Result.CHECK_ERROR;
        case UPGRADE_COST_MISSING -> FactoryRecipeStarter.Result.UPGRADE_COST_MISSING;
        default -> FactoryRecipeStarter.Result.STALE;
      };
    } else if (maxInstances == 1) {
      startResult = host.startSingle(thread.threadId(), plan, plan.parallelBudgetRemaining());
    }
    if (startResult == FactoryRecipeStarter.Result.STARTED) {
      // A committed plan, including its random draws and admissions, is single-use.
      if (searches.remove(thread.threadId(), attempt) && thread.lockedRecipe() != null) {
        var restart = activeSearch.lockedRestart(thread.lockedRecipe().toString(), threadConfiguration.revision());
        if (restart != null) {
          lockedRestarts.put(thread.threadId(), restart);
        }
      }
      threadConfiguration
          .updateThreadState(thread.threadId(), FactoryThreadState.State.RUNNING, FactoryThreadState.Reason.NONE);
      retries.reset(thread.threadId());
    } else {
      var reason = switch (startResult) {
        case CHECK_REJECTED -> FactoryThreadState.Reason.NO_RECIPE;
        case CHECK_PENDING -> FactoryThreadState.Reason.SEARCH_BUDGET;
        case CHECK_ERROR -> FactoryThreadState.Reason.CHECK_ERROR;
        case UPGRADE_COST_MISSING -> FactoryThreadState.Reason.UPGRADE_COST_MISSING;
        default -> FactoryThreadState.Reason.STALE_RESOURCES;
      };
      threadConfiguration.updateThreadState(thread.threadId(), FactoryThreadState.State.WAITING, reason);
      if (startResult == FactoryRecipeStarter.Result.CHECK_REJECTED && activeSearch.rejectSelected(plan)) {
        retries.reset(thread.threadId());
        return;
      }
      if (startResult != FactoryRecipeStarter.Result.CHECK_PENDING) {
        clearThread(thread.threadId());
        backoffSearch(thread.threadId(), now);
      }
    }
  }

  private void recordSearchAttempt(String threadId) {
    threadConfiguration.recordAttempt(threadId, host.hasRuns(threadId));
  }

  public void invalidate() {
    searches.clear();
    lockedRestarts.clear();
    interfaceFailures.clear();
    wakeRetries();
  }

  public void invalidateThread(String threadId, long now) {
    clearThread(threadId);
    lockedRestarts.remove(threadId);
    retries.reset(threadId);
  }

  private void clearThread(String threadId) {
    searches.remove(threadId);
    missingInputs.remove(threadId);
    interfaceFailures.remove(threadId);
  }

  public String interfaceFailure(String threadId) {
    return interfaceFailures.getOrDefault(threadId, "");
  }

  private void backoffSearch(String threadId, long now) {
    retries.defer(threadId, now);
  }

  /**
   * Resource changes wake idle retries without discarding an in-flight search.
   */
  public void wakeRetries() {
    missingInputs.clear();
    inputRevision++;
    wakeRevision++;
    retries.wake();
  }

  public void wakeRetries(MachinePortBlockEntity port) {
    if (port.canGroupInput()) {
      inputRevision++;
      missingInputs.values().removeIf(miss -> miss.portChanged(port));
    }
    wakeRevision++;
    retries.wake();
  }

  static final class Retries {
    private record Retry(long after, int interval) {
    }
    private final Map<String, Retry> deferred = new HashMap<>();

    boolean waiting(String threadId, long now) {
      var retry = deferred.get(threadId);
      return retry != null && now < retry.after();
    }

    void defer(String threadId, long now) {
      var previous = deferred.get(threadId);
      int delay = previous == null ? SEARCH_RETRY_INITIAL : Math.min(SEARCH_RETRY_MAX, previous.interval() * 2);
      deferred.put(threadId, new Retry(now + delay, delay));
    }

    void reset(String threadId) {
      deferred.remove(threadId);
    }

    void deferVerified(String threadId, long after) {
      deferred.put(threadId, new Retry(after, SEARCH_RETRY_MAX));
    }

    void wake() {
      if (!deferred.isEmpty()) {
        deferred.clear();
      }
    }
  }
}
