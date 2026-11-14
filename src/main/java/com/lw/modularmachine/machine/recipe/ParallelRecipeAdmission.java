package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.recipe.search.ChemicalOutputLayout;
import com.lw.modularmachine.machine.recipe.search.FluidOutputLayout;
import com.lw.modularmachine.machine.recipe.search.FluidResourceKey;
import com.lw.modularmachine.machine.recipe.search.ItemOutputLayout;
import com.lw.modularmachine.machine.recipe.search.RecipeCandidate;
import com.lw.modularmachine.machine.recipe.search.RecipePlanner;
import com.lw.modularmachine.machine.PortKind;
import java.util.HashMap;
import java.util.HashSet;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Pure-snapshot binary admission; -1 means yield, zero means no feasible batch.
 */
final class ParallelRecipeAdmission {
  private final RecipeStartPlan plan;
  private final MachineRecipe definition;
  private final ItemOutputLayout layout;
  private final FluidOutputLayout fluids;
  private final boolean aggregateDefinition;
  private int low = 1, high, probe;
  private RecipePlanner.Session<ItemResourceKey> items;
  private FluidOutputLayout.Planning fluidPlan;
  private ChemicalOutputLayout.Planning chemicalPlan;
  private int remainingBudget;

  ParallelRecipeAdmission(RecipeStartPlan plan, MachineRecipe definition, int limit, boolean aggregateDefinition) {
    this.plan = plan;
    this.definition = definition;
    this.aggregateDefinition = aggregateDefinition;
    high = Math.min(
        definition.parallelized() ? limit : Math.min(limit, 1),
        Math.min(plan.maxRepeatableInputs(), plan.maxRepeatableOutputs()));
    high = ChemicalTransactions.arithmeticLimit(definition, high);
    var requiredFluids = new HashMap<FluidResourceKey, Long>();
    var fixedFluids = new HashMap<FluidResourceKey, Long>();
    for (var requirement : definition.fluidInputRequirements()) {
      var fluid = requirement.stack();
      (aggregateDefinition && requirement.parallelUnaffected() ? fixedFluids : requiredFluids).merge(
          new FluidResourceKey(fluid, plan.source().level().registryAccess()),
          (long) fluid.getAmount(),
          Math::addExact);
      if (aggregateDefinition && !requirement.parallelUnaffected()) {
        high = Math.min(high, Integer.MAX_VALUE / fluid.getAmount());
      }
    }
    var fluidKeys = new HashSet<>(requiredFluids.keySet());
    fluidKeys.addAll(fixedFluids.keySet());
    for (var key : fluidKeys) {
      long available = 0;
      for (var port : plan.source().ports()) {
        if (port.kind() == PortKind.FLUID_INPUT
            && port.selector().equals(definition.selectors().fluidInput())
            && FluidStack.isSameFluidSameComponents(key.amount(1), port.fluid())) {
          available = Math.addExact(available, port.fluidAmount());
        }
      }
      long fixed = fixedFluids.getOrDefault(key, 0L);
      if (available < fixed) {
        high = 0;
      } else if (requiredFluids.containsKey(key)) {
        high = (int) Math.min(high, (available - fixed) / requiredFluids.get(key));
      }
    }
    if (definition.energyPerTick() > 0) {
      high = (int) Math.min(high, Long.MAX_VALUE / definition.energyPerTick());
    }
    if (definition.energyOutputPerTick() > 0) {
      high = (int) Math.min(high, Long.MAX_VALUE / definition.energyOutputPerTick());
    }
    if (aggregateDefinition) {
      var fee = definition.fluidPerTick();
      if (!fee.isEmpty()) {
        high = Math.min(high, Integer.MAX_VALUE / fee.getAmount());
      }
      for (var output : definition.fluidOutputRequirements()) {
        if (!output.parallelUnaffected()) {
          high = Math.min(high, Integer.MAX_VALUE / output.stack().getAmount());
        }
      }
      for (var output : definition.outputs()) {
        if (output.chance() > 0 && !output.parallelUnaffected()) {
          high = (int) Math.min(high, Long.MAX_VALUE / output.amount());
        }
      }
    }
    var keys = new HashSet<ItemResourceKey>();
    if (!RecipeItemPreparation.transformsOutputs(definition)) {
      plan.recipe().outputs().forEach(output -> keys.add(output.key()));
    }
    layout = new ItemOutputLayout(plan.source(), keys);
    fluids = new FluidOutputLayout(plan.source(), plan.source().level().registryAccess());
  }

  int advance(int budget) {
    remainingBudget = budget;
    if (high < 1) {
      return 0;
    }
    while (low < high) {
      if (budget <= 0) {
        return -1;
      }
      if (fluidPlan == null) {
        budget--;
        probe = low + (int) (((long) high - low + 1) / 2);
        var claims = RecipeItemPreparation.inputCandidate(plan.recipe(), definition).outputs().stream()
            .map(
                output -> new RecipeCandidate.Output<>(
                    output.key(),
                    output.parallelUnaffected() && aggregateDefinition ? output.count() : Math
                        .multiplyExact(output.count(), probe)))
            .toList();
        items = claims.isEmpty() ? null : RecipePlanner.prepareOutputs(
            layout.snapshot(),
            RecipeCandidate.outputOnly(plan.recipe().id() + "/parallel_admission", claims));
        var fluidClaims = definition.fluidOutputRequirements().stream().filter(output -> output.chance() == 1)
            .map(
                requirement -> new RecipeCandidate.Output<>(
                    new FluidResourceKey(requirement.stack(), plan.source().level().registryAccess())
                        .withSelector(definition.selectors().fluidOutput()),
                    requirement.parallelUnaffected() && aggregateDefinition ? requirement.stack().getAmount() : Math
                        .multiplyExact((long) requirement.stack().getAmount(), probe)))
            .toList();
        fluidPlan = fluids.planClaims(fluidClaims);
        chemicalPlan = new ChemicalOutputLayout(plan.source())
            .plan(ChemicalTransactions.scaled(definition.chemicals().outputs(), probe));
      }
      if (fluidPlan.status() == RecipePlanner.Status.BUDGET_EXHAUSTED) {
        var fluidResult = fluidPlan.advance(budget);
        budget -= fluidResult.workUsed();
      }
      if (fluidPlan.status() == RecipePlanner.Status.BUDGET_EXHAUSTED) {
        return -1;
      }
      boolean matches = fluidPlan.status() == RecipePlanner.Status.SELECTED;
      if (matches && chemicalPlan.status() == RecipePlanner.Status.BUDGET_EXHAUSTED) {
        var result = chemicalPlan.advance(budget);
        budget -= result.workUsed();
        if (result.status() == RecipePlanner.Status.BUDGET_EXHAUSTED) {
          return -1;
        }
      }
      matches = matches
          && chemicalPlan.status() == RecipePlanner.Status.SELECTED
          && ChemicalTransactions
              .inputs(plan.source(), ChemicalTransactions.scaled(definition.chemicals().inputs(), probe)) != null;
      if (matches && items != null) {
        var itemResult = items.advance(budget);
        budget -= itemResult.workUsed();
        if (itemResult.status() == RecipePlanner.Status.BUDGET_EXHAUSTED) {
          return -1;
        }
        matches = itemResult.status() == RecipePlanner.Status.SELECTED;
      }
      if (matches) {
        low = probe;
      } else {
        high = probe - 1;
      }
      fluidPlan = null;
      chemicalPlan = null;
      items = null;
    }

    remainingBudget = budget;
    return low;
  }

  int remainingBudget() {
    return remainingBudget;
  }
}
