package com.lw.modularmachine.machine.controller;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.recipe.RecipeRunStatus;
import com.lw.modularmachine.machine.appearance.ControllerAppearanceState;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.recipe.ControllerMetrics;
import com.lw.modularmachine.machine.recipe.RecipeStartPlan;
import com.lw.modularmachine.machine.recipe.AtomicRecipeStarter.Result;
import com.lw.modularmachine.machine.recipe.search.RecipeCatalog;
import com.lw.modularmachine.machine.recipe.search.RecipeSearch;
import com.lw.modularmachine.machine.recipe.search.RecipePlanner.Status;
import com.lw.modularmachine.machine.recipe.search.RecipeResourceRequirements;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.ApiStatus;

/**
 * Ordinary-controller search, start retries and idle diagnostics; does not own
 * paid runs.
 */
@ApiStatus.Internal
public final class ControllerRecipeSearch {
  private static final int RECIPE_RETRY_INITIAL = 5;
  private static final int RECIPE_RETRY_MAX = 80;
  private final Host host;
  private ControllerAppearanceState idleAppearance = ControllerAppearanceState.FORMED;
  private RecipeSearch recipeSearch;
  private RecipeStartPlan preparedRecipe;
  private long recipeRetryAfter;
  private int recipeRetryInterval = RECIPE_RETRY_INITIAL;
  private String lastRecipeFailure;
  private RecipeRunStatus idleRecipeStatus = RecipeRunStatus.IDLE;

  public interface Host {
    RecipeCatalog catalog();
    RecipeSearch beginSearch(RecipeCatalog catalog);
    void started();
    BlockPos position();
  }

  public ControllerRecipeSearch(Host host) {
    this.host = java.util.Objects.requireNonNull(host, "host");
  }

  public ControllerAppearanceState idleAppearance() {
    return idleAppearance;
  }

  public RecipeRunStatus idleStatus() {
    return idleRecipeStatus;
  }

  public void pauseForEnergyTransfer() {
    idleAppearance = ControllerAppearanceState.PAUSED;
  }

  public void structureAttached() {
    invalidate();
    idleAppearance = ControllerAppearanceState.FORMED;
    idleRecipeStatus = RecipeRunStatus.IDLE;
    wakeRetry(0L);
  }

  public void invalidate() {
    recipeSearch = null;
    preparedRecipe = null;
  }

  public void advance(long now, int serverTick) {
    if (now < recipeRetryAfter) {
      return;
    }
    try {
      advanceRecipe(now, serverTick);
    } catch (RuntimeException failure) {
      invalidate();
      idleAppearance = ControllerAppearanceState.ERROR;
      backoffRecipeRetry(now);
      if (!failure.toString().equals(lastRecipeFailure)) {
        lastRecipeFailure = failure.toString();
        ModularMachine.LOGGER.warn("Ordinary recipe preparation failed at {}", host.position(), failure);
      }
    }
  }

  private void advanceRecipe(long now, int serverTick) {
    if (recipeSearch != null && !recipeSearch.dependenciesCurrent()) {
      recipeSearch = null;
      preparedRecipe = null;
      wakeRetry(now);
    }
    if (recipeSearch == null) {
      idleRecipeStatus = RecipeRunStatus.IDLE;
      var catalog = host.catalog();
      if (catalog == null) {
        idleAppearance = ControllerAppearanceState.PAUSED;
        backoffRecipeRetry(now);
        return;
      }
      if (catalog.recipes().candidates().isEmpty()) {
        idleAppearance = ControllerAppearanceState.FORMED;
        backoffRecipeRetry(now);
        return;
      }
      recipeSearch = host.beginSearch(catalog);
      if (recipeSearch == null) {
        idleAppearance = ControllerAppearanceState.PAUSED;
        backoffRecipeRetry(now);
        return;
      }
      if (idleAppearance == ControllerAppearanceState.ERROR) {
        idleAppearance = ControllerAppearanceState.PAUSED;
      }
    }
    if (preparedRecipe == null) {
      long searchStarted = System.nanoTime();
      try {
        var result = recipeSearch.advance(MachineConfig.RECIPE_OUTPUT_WORK.get());
        if (result.status() == Status.BUDGET_EXHAUSTED) {
          return;
        }
        if (result.status() == Status.NO_MATCH) {
          idleAppearance = ControllerAppearanceState.PAUSED;
          idleRecipeStatus = recipeSearch.missingResource().map(RecipeResourceRequirements::ordinaryResult)
              .orElse(RecipeRunStatus.IDLE);
          recipeSearch = null;
          backoffRecipeRetry(now);
          return;
        }
        preparedRecipe = result.plan().orElseThrow();
      } finally {
        ControllerMetrics.recordSearch(System.nanoTime() - searchStarted, serverTick);
      }
    }
    var result = preparedRecipe.start();
    idleRecipeStatus = RecipeRunStatus.IDLE;
    idleAppearance = result == Result.FAILED ? ControllerAppearanceState.ERROR : ControllerAppearanceState.PAUSED;
    if (result == Result.STARTED) {
      host.started();
      wakeRetry(now);
    }
    if (result == Result.CHECK_REJECTED && recipeSearch.rejectSelected(preparedRecipe)) {
      preparedRecipe = null;
      wakeRetry(now);
      return;
    }
    if (result != Result.BUSY) {
      recipeSearch = null;
      preparedRecipe = null;
      if (result == Result.STALE) {
        wakeRetry(now);
      } else if (result != Result.STARTED) {
        backoffRecipeRetry(now);
      }
    }
  }

  public void wakeRetry(long now) {
    recipeRetryInterval = RECIPE_RETRY_INITIAL;
    recipeRetryAfter = now;
  }

  private void backoffRecipeRetry(long now) {
    recipeRetryAfter = now + recipeRetryInterval;
    recipeRetryInterval = Math.min(RECIPE_RETRY_MAX, recipeRetryInterval << 1);
  }

}
