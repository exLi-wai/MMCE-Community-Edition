package com.lw.modularmachine.machine.controller;

import com.lw.modularmachine.api.recipe.RecipeRunStatus;
import com.lw.modularmachine.machine.recipe.ControllerMetrics;
import com.lw.modularmachine.machine.recipe.RecipeRunEngine;
import com.lw.modularmachine.machine.structure.StructureService;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;
/**
 * Ordinary paid-run, candidate search and legacy energy-transfer sequencing.
 */
@ApiStatus.Internal
public final class ControllerExecutionCoordinator {
  public interface Host {
    RecipeRunStatus advanceRun(RecipeRunEngine engine, ServerLevel server);
    void broadcastObserverStatus(ServerLevel server);
    boolean canCommitProduction();
    StructureService.Registration registration();
  }
  private final ControllerRunState runState;
  private final ControllerRecipeSearch recipeSearch;
  private final ControllerEnergyTransfer energyTransfer;
  private final RecipeRunEngine recipeEngine = new RecipeRunEngine();
  private final Host host;

  public ControllerExecutionCoordinator(ControllerRunState runState, ControllerRecipeSearch recipeSearch, ControllerEnergyTransfer energyTransfer, Host host) {
    this.runState = runState;
    this.recipeSearch = recipeSearch;
    this.energyTransfer = energyTransfer;
    this.host = host;
  }
  public void started() {
    recipeEngine.started();
  }
  public void invalidate() {
    recipeEngine.invalidate();
  }
  public RecipeRunStatus status() {
    return recipeEngine.status();
  }

  public void advance(ServerLevel server) {
    if (runState.current() != null) {
      long tickStarted = System.nanoTime();
      var runResult = host.advanceRun(recipeEngine, server);
      ControllerMetrics.recordTick(System.nanoTime() - tickStarted, server.getServer().getTickCount());
      host.broadcastObserverStatus(server);
      if (runState.current() != null || runResult != RecipeRunStatus.FINISHED) {
        return;
      }
      recipeEngine.acknowledgeFinish();
    }
    if (!host.canCommitProduction()) {
      return;
    }
    var ticket = host.registration();
    long amount = ticket.pattern.definition.energyPerTick();
    if (amount <= 0) {
      recipeSearch.advance(server.getGameTime(), server.getServer().getTickCount());
      return;
    }
    recipeSearch.pauseForEnergyTransfer();
    energyTransfer.advance(server, ticket, amount);
  }
}
