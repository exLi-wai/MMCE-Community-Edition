package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.controller.ControllerScriptData;
import com.lw.modularmachine.api.controller.ControllerScriptDataApi;
import com.lw.modularmachine.api.recipe.RecipePreparationEvents.Type;
import com.lw.modularmachine.machine.upgrade.UpgradeBusOwner;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;

/** One owner-budgeted pre-payment decision for a paid batch. */
@ApiStatus.Internal
public final class RecipeTickPreparation {
  public enum Status {
    PENDING,
    ALLOWED,
    PAUSED,
    STALE
  }

  private final UpgradeBusOwner owner;
  private final ServerLevel level;
  private final String threadId;
  private final List<FrozenRecipeRun> runs;
  private final long epoch = RecipePreparationHandlers.epoch();
  private final Optional<ControllerScriptData> scriptData;
  private RecipePreparationHandlers.Session session;
  private int cursor;
  private Status status = Status.PENDING;
  private long decisionTick = Long.MIN_VALUE;

  public RecipeTickPreparation(UpgradeBusOwner owner, String threadId, List<FrozenRecipeRun> runs) {
    this.owner = owner;
    level = (ServerLevel) owner.getLevel();
    this.threadId = threadId;
    this.runs = List.copyOf(runs);
    scriptData = ControllerScriptDataApi.inspect(level, owner.getBlockPos());
  }

  public boolean reusable(List<FrozenRecipeRun> expectedRuns) {
    return runs.equals(expectedRuns) && current() && (status == Status.PENDING || decisionTick == level.getGameTime());
  }

  public boolean current() {
    return epoch == RecipePreparationHandlers.epoch()
        && owner.getLevel() == level
        && !owner.isRemoved()
        && level.hasChunkAt(owner.getBlockPos())
        && level.getBlockEntity(owner.getBlockPos()) == owner
        && owner.canCommitStructure()
        && owner.containsUpgradeRuns(threadId, runs)
        && scriptData.equals(ControllerScriptDataApi.inspect(level, owner.getBlockPos()));
  }

  public int advance(int budget) {
    if (!current()) {
      status = Status.STALE;
      return 0;
    }
    int used = 0;
    while (status == Status.PENDING && cursor < runs.size() && used < budget) {
      var run = runs.get(cursor);
      if (session == null) {
        used++;
        session = RecipePreparationHandlers
            .begin(owner, run.recipeId(), threadId, Type.PRE_TICK, run.parallelInstances(), run);
      }
      used += session.advance(budget - used);
      if (!current()) {
        status = Status.STALE;
      } else if (session.rejected()) {
        status = Status.PAUSED;
      } else if (session.complete()) {
        session = null;
        cursor++;
      } else {
        break;
      }
    }
    if (status == Status.PENDING && cursor == runs.size()) {
      status = Status.ALLOWED;
    }
    if (status != Status.PENDING) {
      decisionTick = level.getGameTime();
    }
    return used;
  }

  public Status status() {
    return current() ? status : Status.STALE;
  }
}
