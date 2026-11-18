package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecutionRegistry;
import com.lw.modularmachine.machine.recipe.search.RecipeResourceRequirements;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.factory.FactoryThreadState;
import java.util.HashMap;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import java.util.Optional;
import com.lw.modularmachine.machine.upgrade.UpgradeRecipeCheckHandlers;

/** Atomic item-input commit for one independent factory channel. */
public final class FactoryRecipeStarter {
  public enum Result {
    STARTED,
    BUSY,
    STALE,
    UNMANAGED_RECIPE,
    FAILED,
    CHECK_REJECTED,
    CHECK_PENDING,
    CHECK_ERROR,
    UPGRADE_COST_MISSING
  }

  private FactoryRecipeStarter() {
  }

  public static Result start(FactoryControllerBlockEntity factory, String threadId, RecipeStartPlan plan) {
    return start(factory, threadId, plan, MachineConfig.FACTORY_SEARCH_WORK.get());
  }

  public static Result start(FactoryControllerBlockEntity factory, String threadId, RecipeStartPlan plan, int workBudget) {
    if (workBudget < 0) {
      throw new IllegalArgumentException("Negative factory start budget");
    }
    if (factory == null || threadId == null || plan == null || !factory.runState().beginRunStart(threadId)) {
      return Result.BUSY;
    }
    boolean committed = false;
    var resources = new FactoryStartupResources(factory, threadId);
    var patterns = resources.patterns();
    RecipeResourceExecutionRegistry.Prepared additionalResources = null;
    try {
      var nativeRecipe = MachineRecipeService.definitionForStart(factory, plan);
      if (nativeRecipe.isEmpty()) {
        return Result.UNMANAGED_RECIPE;
      }
      int checkBudget = workBudget;
      var admission = plan.advanceFactoryChecks(factory, threadId, checkBudget);
      checkBudget -= admission.workUsed();
      switch (admission.status()) {
        case REJECTED -> {
          return Result.CHECK_REJECTED;
        }
        case PENDING -> {
          return Result.CHECK_PENDING;
        }
        case ERROR -> {
          return Result.CHECK_ERROR;
        }
        case STALE -> {
          return Result.STALE;
        }
        case ALLOWED -> {
        }
      }
      var checked = plan.checkedTicket();
      if (checked.isEmpty() || plan.maxRepeatableInputs() < 1) {
        return Result.STALE;
      }
      var ticket = checked.orElseThrow();
      if (RecipeResourceRequirements.missing(ticket, nativeRecipe.orElseThrow()).isPresent()) {
        return Result.STALE;
      }
      if (RecipeItemPreparation.transformsOutputs(nativeRecipe.orElseThrow()) && !plan.hasPreparedOutputRuns(1)) {
        plan.withOutputPreparationContext(threadId);
        int preparedOutputs = plan.prepareParallel(nativeRecipe.orElseThrow(), 1, checkBudget);
        checkBudget = plan.parallelBudgetRemaining();
        if (preparedOutputs < 0) {
          return Result.CHECK_PENDING;
        }
        if (preparedOutputs == 0) {
          return Result.CHECK_REJECTED;
        }
      }
      var outputCheck = plan.prepareStartOutputs(nativeRecipe.orElseThrow(), ticket, 1, checkBudget, false);
      checkBudget -= outputCheck.workUsed();
      if (outputCheck.status() == RecipeResourcePlanning.Status.PENDING) {
        return Result.CHECK_PENDING;
      }
      if (outputCheck.status() != RecipeResourcePlanning.Status.READY) {
        return Result.STALE;
      }
      var ports = new HashMap<String, RecipePortSnapshot>();
      for (var port : ticket.ports()) {
        ports.put(port.partId(), port);
      }
      var replacements = new HashMap<String, NonNullList<ItemStack>>();
      for (var deduction : plan.deductions()) {
        var target = resources.prepareDeduction(deduction, ports, replacements);
        if (!target.accepted()) {
          return Result.STALE;
        }
        var contents = target.contents();
        if (contents == null) {
          continue;
        }
        int slot = deduction.slot().slot();
        if (slot >= contents.size()
            || !deduction.key().matches(contents.get(slot))
            || contents.get(slot).getCount() < deduction.count()) {
          return Result.STALE;
        }
        contents.set(
            slot,
            contents.get(slot).copyWithCount(Math.toIntExact(contents.get(slot).getCount() - deduction.count())));
      }
      var fluidDebits = StartupFluidAllocation
          .plan(ticket, nativeRecipe.orElseThrow().fluidInputs(), nativeRecipe.orElseThrow().selectors().fluidInput());
      if (fluidDebits == null) {
        return Result.STALE;
      }
      var startCosts = plan.prepareFactoryStartup(factory, threadId, 1);
      if (!startCosts.current()) {
        return Result.STALE;
      }
      if (!startCosts.payable()) {
        return Result.UPGRADE_COST_MISSING;
      }
      var prepared = startCosts.advance(checkBudget);
      switch (prepared.status()) {
        case PENDING -> {
          return Result.CHECK_PENDING;
        }
        case ERROR -> {
          return Result.CHECK_ERROR;
        }
        case STALE -> {
          return Result.STALE;
        }
        case READY -> {
        }
      }
      var upgrades = factory.scheduler().channels().stream().filter(thread -> thread.threadId().equals(threadId))
          .map(FactoryThreadState::upgradeSnapshot).findFirst().orElseThrow();
      var preparedRuns = plan.preparedOutputRuns(1);
      var run = !preparedRuns.isEmpty() ? preparedRuns.getFirst()
          .atStartTick(factory.getLevel().getGameTime()) : FrozenRecipeFactory.freeze(
              ResourceLocation.parse(plan.recipe().id()),
              nativeRecipe.orElseThrow(),
              ticket.definitionEpoch(),
              factory.getLevel().registryAccess(),
              ticket.allPorts(),
              factory.getLevel().getGameTime(),
              upgrades,
              ticket.inputGroup(),
              1,
              ticket.dynamicLengths(),
              RecipeItemPreparation
                  .context(ticket, ResourceLocation.parse(plan.recipe().id()), Optional.of(threadId), 1));
      var paidStart = plan.preparePaidResources(ticket, List.of(run), checkBudget - prepared.workUsed());
      if (paidStart.status() == RecipeResourcePlanning.Status.PENDING) {
        return Result.CHECK_PENDING;
      }
      additionalResources = paidStart.prepared();
      if (additionalResources == null) {
        return Result.STALE;
      }
      resources.enlistAdditional(additionalResources);
      var inputCheck = RecipeItemPreparation
          .checkInputs(nativeRecipe.orElseThrow(), ticket, Optional.of(threadId), 1, run);
      if (inputCheck != RecipeItemCallbackService.InputCheck.ALLOWED) {
        if (!plan.factoryAdmissionCurrent(factory, threadId) || !startCosts.current() || !plan.catalog().isCurrent()) {
          return Result.STALE;
        }
        return switch (inputCheck) {
          case REJECTED -> Result.CHECK_REJECTED;
          case ERROR -> Result.CHECK_ERROR;
          default -> Result.STALE;
        };
      }
      fluidDebits = StartupFluidAllocation.plan(
          ticket,
          run.fluidInputClaims(factory.getLevel().registryAccess()),
          nativeRecipe.orElseThrow().selectors().fluidInput());
      if (fluidDebits == null) {
        return Result.STALE;
      }
      if (!RecipeItemPayments
          .apply(ticket, nativeRecipe.orElseThrow(), 1, false, plan.inputChanceSeed(), replacements)) {
        return Result.STALE;
      }
      if (!plan.factoryAdmissionCurrent(factory, threadId)
          || !plan.preparedOutputsCurrent(1)
          || !run.preparedItemCallbacksCurrent()
          || !factory.recipeThreadAllowed(threadId, run.recipeId(), nativeRecipe.orElseThrow().executionOptions())) {
        return Result.STALE;
      }
      patterns.bindStart(ticket, List.of(run));
      for (var debit : fluidDebits) {
        var port = ports.get(debit.partId());
        if (port.patternInput()) {
          patterns.consumeFluid(port, debit.amount());
        }
      }
      additionalResources.addPatterns(patterns);
      if (!additionalResources.current() || !plan.startOutputsCurrent(nativeRecipe.orElseThrow(), 1, false)) {
        return Result.STALE;
      }
      patterns.acquire();
      startCosts.acquireInto(resources.upgradeLeases());
      additionalResources.acquire();
      if (!run.preparedItemCallbacksCurrent()) {
        return Result.STALE;
      }
      resources.acquire(ticket, replacements, !fluidDebits.isEmpty());
      for (var debit : fluidDebits) {
        if (!ports.get(debit.partId()).patternInput() && !resources.drainFluid(debit.partId(), debit.amount())) {
          return Result.STALE;
        }
      }
      factory.runState().installRun(threadId, run);
      committed = true;
      return Result.STARTED;
    } catch (UpgradeRecipeCheckHandlers.StaleException stale) {
      return Result.STALE;
    } catch (RuntimeException failure) {
      ModularMachine.LOGGER.warn("Factory recipe start failed at {}", factory.getBlockPos(), failure);
      return Result.FAILED;
    } finally {
      resources.resolve(committed);
      if (committed) {
        resources.publishCommitted();
      }
    }
  }

}
