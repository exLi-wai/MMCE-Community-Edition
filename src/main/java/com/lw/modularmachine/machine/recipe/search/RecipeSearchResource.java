package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.recipe.search.index.ResourceInputQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.ApiStatus;

/**
 * Catalog-owned resource adaptation; capture and exact checks run on the server
 * thread.
 */
@ApiStatus.Internal
public interface RecipeSearchResource {
  Captured capture(RecipeExecutionTicket ticket, RecipeSearchResources resources);

  interface Replenishment extends RecipeResourcePlanning {
    boolean waiting();

    boolean requested();

    Progress advanceRequests(int budget, BooleanSupplier current);
  }

  interface Recheckable extends RecipeResourcePlanning {
    RecipeResourcePlanning recheck(ReplenishmentProof proof);
  }

  /**
   * A sliced stock or capacity check is usable only while its observations remain
   * valid.
   */
  final class ReplenishmentProof {
    private final List<BooleanSupplier> liveChecks = new ArrayList<>();
    private LongSupplier gameTime;
    private long expires = Long.MAX_VALUE;
    private boolean current = true;

    public boolean current() {
      return current && (gameTime == null || gameTime.getAsLong() <= expires);
    }

    public void invalidate() {
      current = false;
    }

    public void observeExpiry(long expires, LongSupplier gameTime) {
      if (expires < this.expires) {
        this.expires = expires;
        this.gameTime = gameTime;
      }
    }

    public void addLiveCheck(BooleanSupplier check) {
      liveChecks.add(check);
    }

    int liveWork() {
      return liveChecks.size();
    }

    public boolean liveCurrent() {
      return current() && liveChecks.stream().allMatch(BooleanSupplier::getAsBoolean) && current();
    }
  }

  /**
   * One input group's query and planning state; it never reserves physical
   * resources.
   */
  interface Captured {
    ResourceInputQuery inputs();

    default boolean available(String recipeId, MachineRecipe definition) {
      return true;
    }

    default RecipeResourcePlanning planInput(String recipeId, MachineRecipe definition) {
      return ResourcePlanningSequence.check(() -> available(recipeId, definition));
    }

    default boolean inputCurrent(String recipeId, MachineRecipe definition, RecipeResourcePlanning plan) {
      return available(recipeId, definition);
    }

    RecipeResourcePlanning outputs(String recipeId, MachineRecipe definition);
  }
}
