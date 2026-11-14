package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.machine.factory.UpgradeRecipeChecks;
import com.lw.modularmachine.machine.recipe.search.ItemOutputLayout;
import com.lw.modularmachine.machine.recipe.search.RecipeCandidate;
import com.lw.modularmachine.machine.recipe.search.RecipePlanner;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal unpaid, main-thread output preparation reused by the start
 * transaction.
 */
@ApiStatus.Internal
final class PreparedRecipeOutputs {
  private final RecipeStartPlan plan;
  private final MachineRecipe recipe;
  private final String thread;
  private final boolean aggregate;
  private int instances;
  private final List<FrozenItemRun> runs = new ArrayList<>();
  private RecipeExecutionTicket outputs;
  private RecipePlanner.Session<ItemResourceKey> placement;
  private boolean admitted;
  private int remainingBudget;

  PreparedRecipeOutputs(RecipeStartPlan plan, MachineRecipe recipe, String thread, int instances) {
    this.plan = plan;
    this.recipe = recipe;
    this.thread = thread;
    this.instances = instances;
    aggregate = plan.source().owner() instanceof MachineControllerBlockEntity;
  }

  int advance(int budget) {
    remainingBudget = budget;
    var source = plan.source();
    source.requireServerThread();
    if (!plan.validateForStart() || runs.stream().anyMatch(run -> !run.preparedItemCallbacksCurrent())) {
      throw new UpgradeRecipeChecks.StaleException();
    }
    if (admitted) {
      if (!refreshOutputs()) {
        throw new UpgradeRecipeChecks.StaleException();
      }
      return instances;
    }
    while (instances > 0 && remainingBudget > 0) {
      int requiredRuns = aggregate ? 1 : instances;
      if (runs.size() < requiredRuns) {
        var upgrades = aggregate ? source.ordinaryUpgrades() : ((FactoryControllerBlockEntity) source.owner())
            .scheduler().channel(thread).upgradeSnapshot();
        var context = RecipeItemPreparation.context(
            source,
            ResourceLocation.parse(plan.recipe().id()),
            aggregate ? Optional.empty() : Optional.of(thread),
            aggregate ? instances : 1);
        runs.add(
            FrozenItemRun.freeze(
                ResourceLocation.parse(plan.recipe().id()),
                recipe,
                source.definitionEpoch(),
                source.level().registryAccess(),
                source.allPorts(),
                source.level().getGameTime(),
                upgrades,
                source.inputGroup(),
                aggregate ? instances : 1,
                source.dynamicLengths(),
                context));
        remainingBudget--;
        if (!plan.validateForStart() || !runs.getLast().preparedItemCallbacksCurrent()) {
          throw new UpgradeRecipeChecks.StaleException();
        }
        continue;
      }
      if (placement == null) {
        outputs = RecipeExecutionTicket.captureOutputs(source.owner())
            .orElseThrow(UpgradeRecipeChecks.StaleException::new);
        var claims = new ArrayList<RecipeCandidate.Output<ItemResourceKey>>();
        runs.forEach(run -> claims.addAll(run.startOutputClaims(source.level().registryAccess())));
        if (claims.isEmpty()) {
          admitted = true;
          return instances;
        }
        var keys = new HashSet<ItemResourceKey>();
        claims.forEach(claim -> keys.add(claim.key()));
        placement = RecipePlanner.prepareOutputs(
            new ItemOutputLayout(outputs, keys).snapshot(),
            RecipeCandidate.outputOnly(plan.recipe().id() + "/prepared_outputs", claims));
      }
      if (!refreshOutputs()) {
        throw new UpgradeRecipeChecks.StaleException();
      }
      var result = placement.advance(remainingBudget);
      remainingBudget -= result.workUsed();
      if (result.status() == RecipePlanner.Status.BUDGET_EXHAUSTED) {
        return -1;
      }
      if (result.status() == RecipePlanner.Status.SELECTED) {
        admitted = true;
        return instances;
      }
      // Modifiers may depend on the requested count, so capacity is not monotonic.
      instances--;
      placement = null;
      if (aggregate) {
        runs.clear();
      } else if (!runs.isEmpty()) {
        runs.removeLast();
      }
    }
    return instances == 0 ? 0 : -1;
  }

  List<FrozenItemRun> runs(int requested) {
    if (!admitted || requested != instances) {
      throw new IllegalStateException("Modified outputs have not passed admission");
    }
    return List.copyOf(runs);
  }

  boolean admittedWithin(int limit) {
    return admitted && instances <= limit;
  }

  boolean rejected() {
    return instances == 0;
  }

  boolean current(int requested) {
    return admitted
        && instances == requested
        && refreshOutputs()
        && runs.stream().allMatch(FrozenItemRun::preparedItemCallbacksCurrent);
  }

  int remainingBudget() {
    return remainingBudget;
  }

  private boolean refreshOutputs() {
    if (outputs == null) {
      return false;
    }
    var refreshed = outputs.refreshIfUnchanged().filter(RecipeCommitGuard::validate);
    if (refreshed.isEmpty()) {
      return false;
    }
    outputs = refreshed.orElseThrow();
    return true;
  }
}
