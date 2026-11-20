package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.machine.recipe.ControllerMetrics;
import com.lw.modularmachine.machine.recipe.MachineRecipeService;
import com.lw.modularmachine.api.controller.VerificationState;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;
/** Factory thread gates and the ordered upgrade, paid-run and search visit. */
@ApiStatus.Internal
public final class FactoryExecutionCoordinator {
  public interface Host {
    VerificationState verificationState();
    boolean persistentStateValid();
  }
  private final FactoryThreadConfiguration threadConfiguration;
  private final FactoryUpgradeSources upgrades;
  private final FactoryRunCoordinator runCoordinator;
  private final FactorySearchCoordinator searchCoordinator;
  private final Host host;

  public FactoryExecutionCoordinator(FactoryThreadConfiguration threadConfiguration, FactoryUpgradeSources upgrades, FactoryRunCoordinator runCoordinator, FactorySearchCoordinator searchCoordinator, Host host) {
    this.threadConfiguration = threadConfiguration;
    this.upgrades = upgrades;
    this.runCoordinator = runCoordinator;
    this.searchCoordinator = searchCoordinator;
    this.host = host;
  }

  public void advance(ServerLevel server) {
    threadConfiguration.initializeIfNeeded();
    upgrades.refreshBindings(server);
    refreshThreadGates();
    refreshLockedRecipeGates(server);
    long tickStarted = System.nanoTime();
    runCoordinator.advance();
    ControllerMetrics.recordTick(System.nanoTime() - tickStarted, server.getServer().getTickCount());
    upgrades.refreshSnapshot();
    long searchStarted = System.nanoTime();
    searchCoordinator.advance();
    ControllerMetrics.recordSearch(System.nanoTime() - searchStarted, server.getServer().getTickCount());
  }

  private void refreshThreadGates() {
    var state = host.verificationState();
    var reason = switch (state) {
      case CONFIRMED -> FactoryThreadState.Reason.NONE;
      case PENDING -> FactoryThreadState.Reason.STRUCTURE_PENDING;
      case INVALID -> host
          .persistentStateValid() ? FactoryThreadState.Reason.STRUCTURE_INVALID : FactoryThreadState.Reason.CONTROLLER_UNAVAILABLE;
    };
    var target = state == VerificationState.CONFIRMED ? FactoryThreadState.State.IDLE : state == VerificationState.PENDING ? FactoryThreadState.State.WAITING : FactoryThreadState.State.INVALID;
    var next = threadConfiguration.scheduler();
    for (var thread : threadConfiguration.scheduler().channels()) {
      if (thread.state() == FactoryThreadState.State.RUNNING) {
        continue;
      }
      if (state == VerificationState.CONFIRMED
          && thread.reason() != FactoryThreadState.Reason.STRUCTURE_PENDING
          && thread.reason() != FactoryThreadState.Reason.STRUCTURE_INVALID
          && thread.reason() != FactoryThreadState.Reason.CONTROLLER_UNAVAILABLE) {
        continue;
      }
      next = next.replace(thread.withState(target, reason));
    }
    threadConfiguration.publishRuntimeStates(next);
  }

  private void refreshLockedRecipeGates(ServerLevel server) {
    if (host.verificationState() != VerificationState.CONFIRMED) {
      return;
    }
    var next = threadConfiguration.scheduler();
    for (var thread : threadConfiguration.scheduler().channels()) {
      if (thread.lockedRecipe() == null || thread.state() == FactoryThreadState.State.RUNNING) {
        continue;
      }
      var belongs = MachineRecipeService.recipeBelongsTo(server.getServer(), thread.machineId(), thread.lockedRecipe());
      // A valid lock only clears this gate's own error, not resource/run status.
      if (belongs.isEmpty() || belongs.get() && thread.reason() != FactoryThreadState.Reason.LOCKED_RECIPE_MISSING) {
        continue;
      }
      var desired = belongs.get() ? thread
          .withState(FactoryThreadState.State.IDLE, FactoryThreadState.Reason.NONE) : thread
              .withState(FactoryThreadState.State.INVALID, FactoryThreadState.Reason.LOCKED_RECIPE_MISSING);
      next = next.replace(desired);
    }
    threadConfiguration.publishRuntimeStates(next);
  }

}
