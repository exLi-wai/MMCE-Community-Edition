package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.recipe.search.index.ResourceInputQuery;
import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
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

    ResourceLocation nextRequestKey();

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
    private final Map<ResourceLocation, List<BooleanSupplier>> deficitChecks = new HashMap<>();
    private final List<BooleanSupplier> allDeficitChecks = new ArrayList<>();
    private boolean sharedDeficitCredit;
    private final Map<Object, Boolean> connections = new IdentityHashMap<>();
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

    public void observeDeficit(ResourceLocation key, BooleanSupplier check) {
      deficitChecks.computeIfAbsent(key, ignored -> new ArrayList<>()).add(check);
      allDeficitChecks.add(check);
    }

    public void shareDeficitCredit() {
      sharedDeficitCredit = true;
    }

    public void observeConnection(Object hatch, boolean connected, BooleanSupplier connectedNow) {
      var previous = connections.putIfAbsent(hatch, connected);
      if (previous == null) {
        addLiveCheck(() -> connectedNow.getAsBoolean() == connected);
      } else if (previous != connected) {
        invalidate();
      }
    }

    /**
     * Watched observations survive visits. External reads without invalidation must
     * fit this visit, with at most one such read in the publication unit.
     */
    public RecipeResourcePlanning.Progress publishOne(ResourceLocation key, int remaining, int visitBudget, BooleanSupplier timeLeft, Function<BooleanSupplier, RecipeResourcePlanning.Progress> publish) {
      var requested = sharedDeficitCredit ? allDeficitChecks : deficitChecks.getOrDefault(key, List.of());
      int cost = Math.max(1, liveChecks.size() + requested.size());
      if (cost > visitBudget || !current()) {
        return new RecipeResourcePlanning.Progress(RecipeResourcePlanning.Status.REJECTED, 0);
      }
      if (cost > remaining || !timeLeft.getAsBoolean()) {
        return new RecipeResourcePlanning.Progress(RecipeResourcePlanning.Status.PENDING, 0);
      }
      int used = 0;
      for (var check : Iterables.concat(liveChecks, requested)) {
        used++;
        if (!check.getAsBoolean() || !current() || !timeLeft.getAsBoolean()) {
          return new RecipeResourcePlanning.Progress(RecipeResourcePlanning.Status.REJECTED, used);
        }
      }
      var published = publish.apply(() -> current() && timeLeft.getAsBoolean());
      return new RecipeResourcePlanning.Progress(published.status(), Math.max(used, published.workUsed()));
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
