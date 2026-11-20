package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import com.lw.modularmachine.api.upgrade.UpgradeSnapshot;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecutionRegistry;
import com.lw.modularmachine.machine.recipe.search.FluidOutputLayout;
import com.lw.modularmachine.machine.recipe.search.FluidResourceKey;
import com.lw.modularmachine.machine.recipe.search.ItemOutputLayout;
import com.lw.modularmachine.machine.recipe.search.RecipeCandidate;
import com.lw.modularmachine.machine.recipe.search.RecipePlanner;
import com.lw.modularmachine.machine.recipe.search.ResourcePlanningAdapter;
import com.lw.modularmachine.machine.recipe.search.ResourcePlanningSequence;
import com.lw.modularmachine.machine.PortKind;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Pure-snapshot binary admission; -1 means yield, zero means no feasible batch.
 */
final class ParallelRecipeAdmission {
  private final RecipeStartPlan plan;
  private final MachineRecipe definition;
  private ItemOutputLayout layout;
  private FluidOutputLayout fluids;
  private final boolean aggregateDefinition;
  private final ParallelAdmissionSearch search;

  ParallelRecipeAdmission(RecipeStartPlan plan, MachineRecipe definition, int limit, boolean aggregateDefinition, UpgradeSnapshot upgrades) {
    this.plan = plan;
    this.definition = definition;
    this.aggregateDefinition = aggregateDefinition;
    int high = definition.parallelized() ? limit : Math.min(limit, 1);
    high = plan.maxRepeatableInputs(Math.min(high, plan.maxRepeatableOutputs()));
    high = ChemicalTransactions.arithmeticLimit(definition, high, aggregateDefinition);
    high = RecipeTriggeredResources.arithmeticLimit(definition, high, aggregateDefinition, upgrades);
    high = RecipeRandomItemOutputs.arithmeticLimit(definition, high, aggregateDefinition);
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
    if (aggregateDefinition) {
      high = FrozenRecipeFactory.aggregateTickLimit(
          high,
          definition.energyPerTick(),
          definition.energyOutputPerTick(),
          definition.fluidPerTick().getAmount(),
          upgrades);
      for (var input : definition.fluidPerTickInputs()) {
        try {
          long amount = upgrades.fluidPerTick(input.stack().getAmount());
          high = amount == 0 ? high : (int) Math.min(high, Integer.MAX_VALUE / amount);
        } catch (ArithmeticException overflow) {
          high = 0;
        }
      }
      for (var output : definition.fluidPerTickOutputs()) {
        high = Math.min(high, Integer.MAX_VALUE / output.stack().getAmount());
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
    } else {
      high = FrozenRecipeFactory
          .factoryTickLimit(high, definition.energyPerTick(), definition.energyOutputPerTick(), upgrades);
    }
    search = new ParallelAdmissionSearch(high, this::prepareProbe);
  }

  int advance(int budget) {
    return search.advance(budget);
  }

  private RecipeResourcePlanning prepareProbe(int instances) {
    return new ResourcePlanningSequence(
        List.of(
            () -> prepareFluidOutputs(instances),
            () -> RecipeResourceExecutionRegistry.prepareAdmission(plan.source(), definition.resourceData(), instances),
            () -> prepareItemOutputs(instances)));
  }

  private RecipeResourcePlanning prepareFluidOutputs(int instances) {
    if (fluids == null) {
      fluids = new FluidOutputLayout(plan.source(), plan.source().level().registryAccess());
    }
    var claims = definition.fluidOutputRequirements().stream()
        .filter(output -> output.chance() == 1 && !output.ignoreOutputCheck())
        .map(
            requirement -> new RecipeCandidate.Output<>(
                new FluidResourceKey(requirement.stack(), plan.source().level().registryAccess())
                    .withSelector(definition.selectors().fluidOutput()),
                requirement.parallelUnaffected() && aggregateDefinition ? requirement.stack().getAmount() : Math
                    .multiplyExact((long) requirement.stack().getAmount(), instances),
                false,
                requirement.ignoreOutputCheck()))
        .toList();
    var planning = fluids.planClaims(claims);

    return new ResourcePlanningAdapter<>(planning::status, planning::advance);
  }

  private RecipeResourcePlanning prepareItemOutputs(int instances) {
    var claims = RecipeItemPreparation.inputCandidate(plan.recipe(), definition).outputs().stream()
        .filter(output -> !output.ignoreOutputCheck())
        .map(
            output -> new RecipeCandidate.Output<>(
                output.key(),
                output.parallelUnaffected() && aggregateDefinition ? output.count() : Math
                    .multiplyExact(output.count(), instances),
                false,
                output.ignoreOutputCheck()))
        .toList();
    if (claims.isEmpty()) {
      return new ResourcePlanningSequence(List.of());
    }
    if (layout == null) {
      var keys = new HashSet<ItemResourceKey>();
      claims.forEach(output -> keys.add(output.key()));
      layout = new ItemOutputLayout(plan.source(), keys);
    }
    var planning = RecipePlanner.prepareOutputs(
        layout.snapshot(),
        RecipeCandidate.outputOnly(plan.recipe().id() + "/parallel_admission", claims));

    return new ResourcePlanningAdapter<>(() -> planning.advance(0).status(), planning::advance);
  }

  int remainingBudget() {
    return search.remainingBudget();
  }
}
