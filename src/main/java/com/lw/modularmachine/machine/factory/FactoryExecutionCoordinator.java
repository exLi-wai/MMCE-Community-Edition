package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.machine.recipe.ControllerMetrics;
import com.lw.modularmachine.machine.recipe.MachineRecipeService;
import com.lw.modularmachine.machine.recipe.search.RecipeCatalog;
import com.lw.modularmachine.api.controller.VerificationState;
import com.lw.modularmachine.config.MachineConfig;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;
/** Factory thread gates and the ordered upgrade, paid-run and search visit. */
@ApiStatus.Internal
public final class FactoryExecutionCoordinator {
  public interface Host {
    VerificationState verificationState();
    boolean persistentStateValid();
    RecipeCatalog catalog();
  }
  private final FactoryThreadConfiguration threadConfiguration;
  private final FactoryUpgradeSources upgrades;
  private final FactoryRunCoordinator runCoordinator;
  private final FactorySearchCoordinator searchCoordinator;
  private final ControllerMetrics metrics;
  private final Host host;
  private FactoryScheduler checkedScheduler;
  private RecipeCatalog checkedCatalog;
  private VerificationState checkedVerification;
  private boolean checkedPersistentState;
  private int gateCursor;
  private int gatesRemaining;

  public FactoryExecutionCoordinator(FactoryThreadConfiguration threadConfiguration, FactoryUpgradeSources upgrades, FactoryRunCoordinator runCoordinator, FactorySearchCoordinator searchCoordinator, ControllerMetrics metrics, Host host) {
    this.threadConfiguration = threadConfiguration;
    this.upgrades = upgrades;
    this.runCoordinator = runCoordinator;
    this.searchCoordinator = searchCoordinator;
    this.metrics = metrics;
    this.host = host;
  }

  public void advance(ServerLevel server) {
    threadConfiguration.initializeIfNeeded();
    upgrades.refreshBindings(server);
    long tickStarted = System.nanoTime();
    runCoordinator.advance();
    metrics.recordTick(System.nanoTime() - tickStarted, server.getServer().getTickCount());
    upgrades.refreshSnapshot();
    var state = host.verificationState();
    if (state == VerificationState.CONFIRMED && !upgrades.valid()) {
      // Upgrade diagnostics own idle states until sources recover; then recheck every
      // lock.
      checkedScheduler = null;
      searchCoordinator.invalidate();
      return;
    }
    var catalog = state == VerificationState.CONFIRMED ? host.catalog() : null;
    boolean empty = state == VerificationState.CONFIRMED && (catalog == null || catalog.recipes().size() == 0);
    refreshGates(server, state, catalog, empty);
    searchCoordinator.advance(catalog, metrics, server.getServer().getTickCount());
  }

  private void refreshGates(ServerLevel server, VerificationState state, RecipeCatalog catalog, boolean empty) {
    var scheduler = threadConfiguration.scheduler();
    int size = scheduler.channels().size();
    boolean valid = host.persistentStateValid();
    if (checkedScheduler != scheduler
        || checkedVerification != state
        || checkedCatalog != catalog
        || checkedPersistentState != valid) {
      // An external change requires a fresh pass. Keep moving so frequently
      // changing paid threads cannot starve idle channels at the end of the list.
      gatesRemaining = size;
      gateCursor = size == 0 ? 0 : gateCursor % size;
      checkedScheduler = scheduler;
      checkedVerification = state;
      checkedCatalog = catalog;
      checkedPersistentState = valid;
    }
    if (gatesRemaining == 0) {
      return;
    }
    int count = empty ? Math.min(gatesRemaining, MachineConfig.FACTORY_THREADS_PER_TICK.get()) : gatesRemaining;
    var next = scheduler
        .updateStates(gateCursor, count, thread -> refreshGate(server, thread, state, valid, catalog, empty));
    threadConfiguration.publishRuntimeStates(next);
    checkedScheduler = next;
    gatesRemaining -= count;
    gateCursor = (gateCursor + count) % size;
  }

  private FactoryThreadState refreshGate(ServerLevel server, FactoryThreadState thread, VerificationState state, boolean valid, RecipeCatalog catalog, boolean waitingForRecipes) {
    if (state != VerificationState.CONFIRMED) {
      if (thread.state() == FactoryThreadState.State.RUNNING) {
        return thread;
      }
      if (state == VerificationState.PENDING) {
        return thread.withState(FactoryThreadState.State.WAITING, FactoryThreadState.Reason.STRUCTURE_PENDING);
      }
      var reason = valid ? FactoryThreadState.Reason.STRUCTURE_INVALID : FactoryThreadState.Reason.CONTROLLER_UNAVAILABLE;
      return thread.withState(FactoryThreadState.State.INVALID, reason);
    }
    if (thread.state() != FactoryThreadState.State.RUNNING
        && (thread.reason() == FactoryThreadState.Reason.STRUCTURE_PENDING
            || thread.reason() == FactoryThreadState.Reason.STRUCTURE_INVALID
            || thread.reason() == FactoryThreadState.Reason.CONTROLLER_UNAVAILABLE
                && (catalog != null || thread.state() != FactoryThreadState.State.WAITING))) {
      thread = thread.withState(FactoryThreadState.State.IDLE, FactoryThreadState.Reason.NONE);
    }
    boolean checkLock = thread.lockedRecipe() != null
        && catalog != null
        && thread.state() != FactoryThreadState.State.RUNNING;
    if ((!checkLock && !waitingForRecipes) || runCoordinator.hasRuns(thread.threadId())) {
      return thread;
    }
    if (checkLock) {
      var belongs = MachineRecipeService.recipeBelongsTo(server.getServer(), thread.machineId(), thread.lockedRecipe());
      if (belongs.isPresent()) {
        if (!belongs.get()) {
          return thread.withState(FactoryThreadState.State.INVALID, FactoryThreadState.Reason.LOCKED_RECIPE_MISSING);
        }
        if (thread.reason() == FactoryThreadState.Reason.LOCKED_RECIPE_MISSING) {
          thread = thread.withState(FactoryThreadState.State.IDLE, FactoryThreadState.Reason.NONE);
        }
      }
    }
    if (waitingForRecipes && thread.enabled() && thread.state() != FactoryThreadState.State.INVALID) {
      var reason = catalog == null ? FactoryThreadState.Reason.CONTROLLER_UNAVAILABLE : FactoryThreadState.Reason.NO_RECIPE;
      return thread.withState(FactoryThreadState.State.WAITING, reason);
    }

    return thread;
  }

}
