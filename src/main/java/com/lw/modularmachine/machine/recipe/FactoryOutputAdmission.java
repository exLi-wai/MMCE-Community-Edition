package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecutionRegistry;
import com.lw.modularmachine.machine.recipe.search.FluidOutputLayout;
import com.lw.modularmachine.machine.recipe.search.FluidResourceKey;
import com.lw.modularmachine.machine.recipe.search.ItemOutputLayout;
import com.lw.modularmachine.machine.recipe.search.RecipeCandidate;
import com.lw.modularmachine.machine.recipe.search.RecipePlanner;
import com.lw.modularmachine.machine.recipe.search.ResourcePlanningAdapter;
import com.lw.modularmachine.machine.recipe.search.ResourcePlanningSequence;
import com.lw.modularmachine.machine.upgrade.UpgradeRecipeCheckHandlers;
import java.util.HashSet;
import java.util.List;
import org.jetbrains.annotations.ApiStatus;

/**
 * Unpaid output checks retain their planners and share the caller's work
 * budget.
 */
@ApiStatus.Internal
final class FactoryOutputAdmission {
  private final RecipeStartPlan plan;
  private final MachineRecipe definition;
  private final int instances;
  private RecipeExecutionTicket ticket;
  private final ResourcePlanningSequence sequence;

  FactoryOutputAdmission(RecipeStartPlan plan, MachineRecipe definition, RecipeExecutionTicket ticket, int instances) {
    this.plan = plan;
    this.definition = definition;
    this.ticket = ticket;
    this.instances = instances;
    sequence = new ResourcePlanningSequence(
        List.of(
            this::prepareItems,
            this::prepareFluids,
            () -> RecipeResourceExecutionRegistry.prepareAdmission(this.ticket, definition.resourceData(), instances)));
  }

  boolean matches(MachineRecipe requested, int count) {
    return definition == requested && instances == count;
  }

  RecipeResourcePlanning.Progress advance(int budget) {
    if (budget < 0) {
      throw new IllegalArgumentException("Negative output admission budget");
    }
    if (!refreshTicket()) {
      throw new UpgradeRecipeCheckHandlers.StaleException();
    }

    return sequence.advance(budget);
  }

  boolean current() {
    return sequence.status() == RecipeResourcePlanning.Status.READY && refreshTicket();
  }

  private boolean refreshTicket() {
    var refreshed = ticket.refreshIfUnchanged().filter(RecipeCommitGuard::validate);
    if (refreshed.isEmpty() || !plan.catalog().isCurrent() || !plan.inputPreparationCurrent()) {
      return false;
    }
    ticket = refreshed.orElseThrow();

    return true;
  }

  private RecipeResourcePlanning prepareItems() {
    var claims = RecipeItemPreparation.inputCandidate(plan.recipe(), definition).outputs().stream()
        .filter(claim -> !claim.ignoreOutputCheck())
        .map(
            claim -> new RecipeCandidate.Output<>(
                claim.key(),
                Math.multiplyExact(claim.count(), instances),
                false,
                claim.ignoreOutputCheck()))
        .toList();
    if (claims.isEmpty()) {
      return new ResourcePlanningSequence(List.of());
    }
    var keys = new HashSet<ItemResourceKey>();
    claims.forEach(claim -> keys.add(claim.key()));
    var session = RecipePlanner.prepareOutputs(
        new ItemOutputLayout(ticket, keys).snapshot(),
        RecipeCandidate.outputOnly(plan.recipe().id() + "/factory_output_admission", claims));

    return new ResourcePlanningAdapter<>(() -> session.advance(0).status(), session::advance);
  }

  private RecipeResourcePlanning prepareFluids() {
    var registries = ticket.level().registryAccess();
    var claims = definition.fluidOutputRequirements().stream()
        .filter(output -> output.chance() == 1 && !output.ignoreOutputCheck())
        .map(
            requirement -> new RecipeCandidate.Output<>(
                new FluidResourceKey(requirement.stack(), registries)
                    .withSelector(definition.selectors().fluidOutput()),
                Math.multiplyExact((long) requirement.stack().getAmount(), instances),
                false,
                requirement.ignoreOutputCheck()))
        .toList();
    var planning = new FluidOutputLayout(ticket, registries).planClaims(claims);

    return new ResourcePlanningAdapter<>(planning::status, planning::advance);
  }
}
