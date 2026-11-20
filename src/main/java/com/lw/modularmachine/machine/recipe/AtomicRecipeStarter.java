package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import com.lw.modularmachine.api.component.ResourceType;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceTransaction;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecutionRegistry;
import com.lw.modularmachine.machine.controller.ControllerRunState;
import com.lw.modularmachine.machine.recipe.search.RecipeResourceRequirements;
import com.lw.modularmachine.machine.upgrade.UpgradeRecipeCheckHandlers;
import com.lw.modularmachine.machine.upgrade.UpgradeLifecyclePublisher;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.upgrade.UpgradeStartCosts;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.NonNullList;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import java.util.Optional;

/**
 * Main-thread in-memory atomicity for owned item buses plus the controller's
 * paid run record.
 */
public final class AtomicRecipeStarter {
  private AtomicRecipeStarter() {
  }

  public enum Result {
    STARTED,
    STARTED_WITH_NOTIFICATION_ERROR,
    ALREADY_RUNNING,
    BUSY,
    STALE,
    CHECK_REJECTED,
    UNMANAGED_RECIPE,
    FAILED
  }

  public static Result start(RecipeStartPlan plan) {
    Objects.requireNonNull(plan, "plan");
    plan.source().requireServerThread();
    var controller = plan.source().controller();
    if (controller.recipeStartInProgress()) {
      return Result.BUSY;
    }
    if (controller.runningRecipe().isPresent()) {
      return Result.ALREADY_RUNNING;
    }
    if (!controller.schemaSupported() || controller.isRemoved()) {
      return Result.STALE;
    }
    var nativeRecipe = MachineRecipeService.definitionForStart(controller, plan);
    if (nativeRecipe.isEmpty()) {
      return Result.UNMANAGED_RECIPE;
    }
    if (!nativeRecipe.orElseThrow().executionOptions().threadName().isEmpty()) {
      return Result.STALE;
    }
    if (!plan.validateForStart()) {
      return Result.STALE;
    }
    if (RecipeResourceRequirements.missing(plan.source(), nativeRecipe.orElseThrow()).isPresent()) {
      return Result.STALE;
    }
    int budget = MachineConfig.RECIPE_OUTPUT_WORK.get();
    var checks = plan.advanceFactoryChecks(controller, "main", budget);
    budget -= checks.workUsed();
    if (checks.status() == UpgradeRecipeCheckHandlers.Status.PENDING) {
      return Result.BUSY;
    }
    if (checks.status() != UpgradeRecipeCheckHandlers.Status.ALLOWED) {
      return checks.status() == UpgradeRecipeCheckHandlers.Status.ERROR ? Result.FAILED : Result.STALE;
    }
    int limit = Math.min(plan.source().parallelLimit(), UpgradeStartCosts.maxRuns(controller));
    if (limit < 1) {
      return Result.STALE;
    }
    int instances;
    try {
      instances = plan.prepareParallel(nativeRecipe.orElseThrow(), limit, budget);
      budget = plan.parallelBudgetRemaining();
    } catch (UpgradeRecipeCheckHandlers.StaleException stale) {
      return Result.STALE;
    } catch (RuntimeException failed) {
      ModularMachine.LOGGER.warn("Recipe output preparation failed at {}", controller.getBlockPos(), failed);
      return Result.FAILED;
    }
    if (instances < 0) {
      return Result.BUSY;
    }
    if (instances == 0) {
      return plan.modifiedOutputsRejected() ? Result.CHECK_REJECTED : Result.STALE;
    }
    var startup = plan.prepareFactoryStartup(controller, "main", instances);
    var startupResult = startup.advance(budget);
    if (startupResult.status() == UpgradeStartCosts.Status.PENDING) {
      return Result.BUSY;
    }
    if (startupResult.status() != UpgradeStartCosts.Status.READY || !startup.payable()) {
      return startupResult.status() == UpgradeStartCosts.Status.ERROR ? Result.FAILED : Result.STALE;
    }
    var effectivePlan = plan.scaled(instances);
    var ports = indexPorts(effectivePlan.source());
    if (itemPortsBusy(ports)) {
      return Result.BUSY;
    }
    var leases = new ArrayList<MachinePortBlockEntity.ItemStartLease>();
    var fluidLeases = new HashMap<String, MachinePortBlockEntity.FluidStartLease>();
    RecipeResourceExecutionRegistry.Prepared additionalResources = null;
    var patterns = new PatternInputTransaction();
    var upgradeLeases = new ArrayList<UpgradeBusBlockEntity.StartCostLease>();
    var resourceTransaction = new RecipeResourceTransaction();
    resourceTransaction.phase().enlist(patterns);
    var itemResources = resourceTransaction.phase();
    var fluidResources = resourceTransaction.phase();
    resourceTransaction.phase().track(upgradeLeases, UpgradeBusBlockEntity.StartCostLease::resolve);
    var ownerResources = resourceTransaction.phase();
    var additionalResourcePhase = resourceTransaction.phase();
    boolean committed = false;
    var owner = ownerResources.enlist(controller.runState().acquireStart(), ControllerRunState.RunStartLease::resolve);
    FrozenRecipeRun startedRun = null;
    try {
      var checked = effectivePlan.checkedTicket();
      if (checked.isEmpty()
          || RecipeResourceRequirements.missing(checked.orElseThrow(), nativeRecipe.orElseThrow()).isPresent()) {
        return Result.STALE;
      }
      int resourceBudget = budget - startupResult.workUsed();
      var outputCheck = plan
          .prepareStartOutputs(nativeRecipe.orElseThrow(), checked.orElseThrow(), instances, resourceBudget, false);
      if (outputCheck.status() == RecipeResourcePlanning.Status.PENDING) {
        return Result.BUSY;
      }
      if (outputCheck.status() != RecipeResourcePlanning.Status.READY) {
        return Result.STALE;
      }
      // Allocation, codecs, predicates and callbacks precede inventory leases.
      var run = prepareRun(plan, effectivePlan, checked.orElseThrow(), nativeRecipe.orElseThrow(), instances);
      var paidStart = plan
          .preparePaidResources(checked.orElseThrow(), List.of(run), resourceBudget - outputCheck.workUsed());
      if (paidStart.status() == RecipeResourcePlanning.Status.PENDING) {
        return Result.BUSY;
      }
      additionalResources = paidStart.prepared();
      if (additionalResources == null) {
        return Result.STALE;
      }
      additionalResourcePhase.enlist(additionalResources);
      var inputCheck = RecipeItemPreparation
          .checkInputs(nativeRecipe.orElseThrow(), checked.orElseThrow(), Optional.empty(), instances, run);
      if (inputCheck != RecipeItemCallbackService.InputCheck.ALLOWED) {
        if (!plan.factoryAdmissionCurrent(controller, "main") || !startup.current() || !plan.catalog().isCurrent()) {
          return Result.STALE;
        }
        return switch (inputCheck) {
          case REJECTED -> Result.CHECK_REJECTED;
          case ERROR -> Result.FAILED;
          default -> Result.STALE;
        };
      }
      startedRun = run;
      var replacements = prepareItemDeductions(effectivePlan, ports, patterns);
      if (replacements == null) {
        return Result.STALE;
      }
      if (!RecipeItemPayments.apply(
          checked.orElseThrow(),
          nativeRecipe.orElseThrow(),
          instances,
          true,
          plan.inputChanceSeed(),
          replacements)) {
        return Result.STALE;
      }
      var fluidDebits = planFluidDebits(
          checked.orElseThrow(),
          nativeRecipe.orElseThrow(),
          run,
          instances,
          controller.getLevel().registryAccess());
      if (fluidDebits == null) {
        return Result.STALE;
      }
      if (!plan.factoryAdmissionCurrent(controller, "main")
          || !startup.current()
          || !additionalResources.current()
          || !plan.preparedOutputsCurrent(instances)
          || !run.preparedItemCallbacksCurrent()) {
        return Result.STALE;
      }
      // No external callbacks, codecs, equality predicates or world writes after
      // acquisition.
      patterns.bindStart(checked.orElseThrow(), List.of(run));
      for (var debit : fluidDebits) {
        var port = ports.get(debit.partId());
        if (port.patternInput()) {
          patterns.consumeFluid(port, debit.amount());
        }
      }
      additionalResources.addPatterns(patterns);
      if (!plan.startOutputsCurrent(nativeRecipe.orElseThrow(), instances, false)) {
        return Result.STALE;
      }
      patterns.acquire();
      startup.acquireInto(upgradeLeases);
      additionalResources.acquire();
      if (!run.preparedItemCallbacksCurrent()) {
        return Result.STALE;
      }
      for (var port : checked.orElseThrow().ports()) {
        if (!port.patternInput() && port.kind().resource() == ResourceType.ITEM) {
          leases.add(
              itemResources.enlist(
                  port.identity().acquireItemStart(port.revision()),
                  MachinePortBlockEntity.ItemStartLease::resolve));
        } else if (!port.patternInput() && port.kind() == PortKind.FLUID_INPUT && !fluidDebits.isEmpty()) {
          fluidLeases.put(
              port.partId(),
              fluidResources.enlist(
                  port.identity().acquireFluidStart(port.revision(), port.tankIndex()),
                  MachinePortBlockEntity.FluidStartLease::resolve));
        }
      }
      int index = 0;
      for (var port : checked.orElseThrow().ports()) {
        if (!port.patternInput() && port.kind().resource() == ResourceType.ITEM) {
          var replacement = replacements.get(port.partId());
          if (replacement != null) {
            leases.get(index).replacePrepared(replacement);
          }
          index++;
        }
      }
      for (var debit : fluidDebits) {
        if (!ports.get(debit.partId()).patternInput() && !fluidLeases.get(debit.partId()).drainExact(debit.amount())) {
          return Result.STALE;
        }
      }
      owner.install(run);
      committed = true;
    } catch (UpgradeRecipeCheckHandlers.StaleException stale) {
      return Result.STALE;
    } catch (RuntimeException failure) {
      ModularMachine.LOGGER
          .warn("Recipe start aborted before resource commit at {}", controller.getBlockPos(), failure);
      return Result.FAILED;
    } finally {
      resourceTransaction.resolve(committed);
    }
    patterns.publish();
    additionalResources.publish();
    UpgradeStartCosts.publish(controller, upgradeLeases);
    boolean warning = false;
    try {
      controller.setChanged();
    } catch (RuntimeException error) {
      warning = true;
      ModularMachine.LOGGER.warn("Committed run dirty notification failed", error);
    }
    UpgradeLifecyclePublisher.recipeStarted(controller, "main", List.of(startedRun));
    warning |= RecipeResourceTransaction.publishAll(
        leases,
        MachinePortBlockEntity.ItemStartLease::publish,
        error -> ModularMachine.LOGGER.warn("Committed item-bus notification failed", error));
    warning |= RecipeResourceTransaction.publishAll(
        fluidLeases.values(),
        MachinePortBlockEntity.FluidStartLease::publish,
        error -> ModularMachine.LOGGER.warn("Committed fluid-input notification failed", error));
    return warning ? Result.STARTED_WITH_NOTIFICATION_ERROR : Result.STARTED;
  }

  private static Map<String, RecipePortSnapshot> indexPorts(RecipeExecutionTicket ticket) {
    var ports = new HashMap<String, RecipePortSnapshot>();
    for (var port : ticket.ports()) {
      ports.put(port.partId(), port);
    }

    return ports;
  }

  private static boolean itemPortsBusy(Map<String, RecipePortSnapshot> ports) {
    for (var port : ports.values()) {
      if (!port.patternInput()
          && port.kind().resource() == ResourceType.ITEM
          && port.identity().itemStartInProgress()) {
        return true;
      }
    }

    return false;
  }

  private static FrozenRecipeRun prepareRun(RecipeStartPlan plan, RecipeStartPlan effectivePlan, RecipeExecutionTicket checked, MachineRecipe recipe, int instances) {
    var controller = plan.source().controller();
    var preparedRuns = plan.preparedOutputRuns(instances);
    if (!preparedRuns.isEmpty()) {
      return preparedRuns.getFirst().atStartTick(controller.getLevel().getGameTime());
    }

    return FrozenRecipeFactory.freeze(
        ResourceLocation.parse(plan.recipe().id()),
        recipe,
        plan.source().definitionEpoch(),
        controller.getLevel().registryAccess(),
        effectivePlan.source().allPorts(),
        controller.getLevel().getGameTime(),
        plan.source().ordinaryUpgrades(),
        effectivePlan.source().inputGroup(),
        instances,
        effectivePlan.source().dynamicLengths(),
        RecipeItemPreparation
            .context(checked, ResourceLocation.parse(plan.recipe().id()), Optional.empty(), instances));
  }

  /**
   * Prepares item replacements and pattern claims without acquiring resources.
   */
  private static Map<String, NonNullList<ItemStack>> prepareItemDeductions(RecipeStartPlan plan, Map<String, RecipePortSnapshot> ports, PatternInputTransaction patterns) {
    Map<String, NonNullList<ItemStack>> replacements = new HashMap<>();
    var deductions = new HashSet<ItemSlotRef>();
    for (var deduction : plan.deductions()) {
      var ref = deduction.slot();
      var port = ports.get(ref.partId());
      if (port == null || port.kind() != PortKind.ITEM_INPUT || !deductions.add(ref)) {
        return null;
      }
      if (port.patternInput()) {
        if (!port.matchesItem(ref.slot(), deduction.key())) {
          return null;
        }
        patterns.consumeItem(port, deduction.slot().slot(), deduction.count());
        continue;
      }
      var contents = replacements.computeIfAbsent(ref.partId(), ignored -> port.copyItemContents());
      if (ref.slot() >= contents.size()) {
        return null;
      }
      var before = contents.get(ref.slot());
      if (!deduction.key().matches(before) || before.getCount() < deduction.count()) {
        return null;
      }
      contents.set(ref.slot(), before.copyWithCount(Math.toIntExact(before.getCount() - deduction.count())));
    }

    return replacements;
  }

  private static List<StartupFluidAllocation.Debit> planFluidDebits(RecipeExecutionTicket checked, MachineRecipe recipe, FrozenRecipeRun run, int instances, HolderLookup.Provider registries) {
    var required = StartupFluidAllocation.plan(
        checked,
        recipe.fluidInputRequirements().stream()
            .map(
                requirement -> requirement.stack().copyWithAmount(
                    requirement.parallelUnaffected() ? requirement.stack().getAmount() : Math
                        .multiplyExact(requirement.stack().getAmount(), instances)))
            .toList(),
        recipe.selectors().fluidInput());
    if (required == null) {
      return null;
    }

    return StartupFluidAllocation.plan(checked, run.fluidInputClaims(registries), recipe.selectors().fluidInput());
  }
}
