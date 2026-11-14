package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.recipe.search.RecipeResourceRequirements;
import com.lw.modularmachine.machine.factory.UpgradeRecipeChecks;
import com.lw.modularmachine.machine.factory.UpgradeLifecyclePublisher;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.factory.FactoryUpgradeStartCosts;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
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
    if (checks.status() == UpgradeRecipeChecks.Status.PENDING) {
      return Result.BUSY;
    }
    if (checks.status() != UpgradeRecipeChecks.Status.ALLOWED) {
      return checks.status() == UpgradeRecipeChecks.Status.ERROR ? Result.FAILED : Result.STALE;
    }
    int limit = Math.min(plan.source().parallelLimit(), FactoryUpgradeStartCosts.maxRuns(controller));
    if (limit < 1) {
      return Result.STALE;
    }
    int instances;
    try {
      instances = plan.prepareParallel(nativeRecipe.orElseThrow(), limit, budget);
      budget = plan.parallelBudgetRemaining();
    } catch (UpgradeRecipeChecks.StaleException stale) {
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
    if (startupResult.status() == FactoryUpgradeStartCosts.Status.PENDING) {
      return Result.BUSY;
    }
    if (startupResult.status() != FactoryUpgradeStartCosts.Status.READY || !startup.payable()) {
      return startupResult.status() == FactoryUpgradeStartCosts.Status.ERROR ? Result.FAILED : Result.STALE;
    }
    var effectivePlan = plan.scaled(instances);
    var ports = new HashMap<String, RecipeExecutionTicket.PortSnapshot>();
    for (var port : effectivePlan.source().ports()) {
      ports.put(port.partId(), port);
    }
    for (var port : ports.values()) {
      if (!port.patternInput()
          && port.kind().resource() == PortKind.Resource.ITEM
          && port.identity().itemStartInProgress()) {
        return Result.BUSY;
      }
    }
    var leases = new ArrayList<MachinePortBlockEntity.ItemStartLease>();
    var fluidLeases = new HashMap<String, MachinePortBlockEntity.FluidStartLease>();
    ChemicalTransactions.Batch chemicals = null;
    var patterns = new PatternInputTransaction();
    var upgradeLeases = new ArrayList<UpgradeBusBlockEntity.StartCostLease>();
    boolean committed = false;
    var owner = controller.acquireRecipeStart();
    FrozenItemRun startedRun = null;
    try {
      var checked = effectivePlan.checkedTicket();
      if (checked.isEmpty()
          || RecipeResourceRequirements.missing(checked.orElseThrow(), nativeRecipe.orElseThrow()).isPresent()) {
        return Result.STALE;
      }
      // Payload codecs/allocations, predicates and potentially reentrant work precede
      // inventory
      // leases.
      var preparedRuns = plan.preparedOutputRuns(instances);
      var run = !preparedRuns.isEmpty() ? preparedRuns.getFirst()
          .atStartTick(controller.getLevel().getGameTime()) : FrozenItemRun.freeze(
              ResourceLocation.parse(plan.recipe().id()),
              nativeRecipe.orElseThrow(),
              plan.source().definitionEpoch(),
              controller.getLevel().registryAccess(),
              effectivePlan.source().allPorts(),
              controller.getLevel().getGameTime(),
              plan.source().ordinaryUpgrades(),
              effectivePlan.source().inputGroup(),
              instances,
              effectivePlan.source().dynamicLengths(),
              RecipeItemPreparation.context(
                  checked.orElseThrow(),
                  ResourceLocation.parse(plan.recipe().id()),
                  Optional.empty(),
                  instances));
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
      Map<String, NonNullList<ItemStack>> replacements = new HashMap<>();
      var deductions = new HashSet<ItemSlotRef>();
      for (var deduction : effectivePlan.deductions()) {
        var ref = deduction.slot();
        var port = ports.get(ref.partId());
        if (port == null || port.kind() != PortKind.ITEM_INPUT || !deductions.add(ref)) {
          return Result.STALE;
        }
        if (port.patternInput()) {
          if (!deduction.key().matches(port.items().get(deduction.slot().slot()))) {
            return Result.STALE;
          }
          patterns.consumeItem(port, deduction.slot().slot(), deduction.count());
          continue;
        }
        var contents = replacements.computeIfAbsent(ref.partId(), ignored -> {
          var values = port.items();
          var copy = NonNullList.withSize(values.size(), ItemStack.EMPTY);
          for (int i = 0; i < values.size(); i++) {
            copy.set(i, values.get(i));
          }
          return copy;
        });
        if (ref.slot() >= contents.size()) {
          return Result.STALE;
        }
        var before = contents.get(ref.slot());
        if (!deduction.key().matches(before) || before.getCount() < deduction.count()) {
          return Result.STALE;
        }
        contents.set(ref.slot(), before.copyWithCount(Math.toIntExact(before.getCount() - deduction.count())));
      }
      var fluidDebits = planFluidDebits(
          checked.orElseThrow(),
          nativeRecipe.orElseThrow().fluidInputRequirements().stream()
              .map(
                  requirement -> requirement.stack().copyWithAmount(
                      requirement.parallelUnaffected() ? requirement.stack().getAmount() : Math
                          .multiplyExact(requirement.stack().getAmount(), instances)))
              .toList(),
          nativeRecipe.orElseThrow().selectors().fluidInput());
      if (fluidDebits == null) {
        return Result.STALE;
      }
      fluidDebits = planFluidDebits(
          checked.orElseThrow(),
          run.fluidInputClaims(controller.getLevel().registryAccess()),
          nativeRecipe.orElseThrow().selectors().fluidInput());
      if (fluidDebits == null) {
        return Result.STALE;
      }
      chemicals = ChemicalTransactions.inputs(
          checked.orElseThrow(),
          ChemicalTransactions.scaled(nativeRecipe.orElseThrow().chemicals().inputs(), instances));
      if (chemicals == null) {
        return Result.STALE;
      }
      if (!plan.factoryAdmissionCurrent(controller, "main")
          || !startup.current()
          || !plan.preparedOutputsCurrent(instances)
          || !run.preparedItemCallbacksCurrent()) {
        return Result.STALE;
      }
      // No external callbacks, codecs, equality predicates or world writes inside
      // this critical
      // section.
      patterns.bindStart(checked.orElseThrow(), List.of(run));
      for (var debit : fluidDebits) {
        var port = ports.get(debit.partId());
        if (port.patternInput()) {
          patterns.consumeFluid(port, debit.amount());
        }
      }
      chemicals.addPatterns(patterns);
      patterns.acquire();
      startup.acquireInto(upgradeLeases);
      chemicals.acquire();
      if (!run.preparedItemCallbacksCurrent()) {
        return Result.STALE;
      }
      for (var port : checked.orElseThrow().ports()) {
        if (!port.patternInput() && port.kind().resource() == PortKind.Resource.ITEM) {
          leases.add(port.identity().acquireItemStart(port.revision()));
        } else if (!port.patternInput() && port.kind() == PortKind.FLUID_INPUT && !fluidDebits.isEmpty()) {
          fluidLeases.put(port.partId(), port.identity().acquireFluidStart(port.revision(), port.tankIndex()));
        }
      }
      int index = 0;
      for (var port : checked.orElseThrow().ports()) {
        if (!port.patternInput() && port.kind().resource() == PortKind.Resource.ITEM) {
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
    } catch (RuntimeException failure) {
      ModularMachine.LOGGER
          .warn("Recipe start aborted before resource commit at {}", controller.getBlockPos(), failure);
      return Result.FAILED;
    } finally {
      patterns.resolve(committed);
      // A single decision governs every participant. Uncommitted swaps restore their
      // old backing
      // lists.
      for (var lease : leases) {
        lease.resolve(committed);
      }
      for (var lease : fluidLeases.values()) {
        lease.resolve(committed);
      }
      for (var lease : upgradeLeases) {
        lease.resolve(committed);
      }
      owner.resolve(committed);
      if (chemicals != null) {
        chemicals.resolve(committed);
      }
    }
    patterns.publish();
    chemicals.publish();
    FactoryUpgradeStartCosts.publish(controller, upgradeLeases);
    boolean warning = false;
    try {
      controller.setChanged();
    } catch (RuntimeException error) {
      warning = true;
      ModularMachine.LOGGER.warn("Committed run dirty notification failed", error);
    }
    UpgradeLifecyclePublisher.recipeStarted(controller, "main", List.of(startedRun));
    for (var lease : leases) {
      try {
        lease.publish();
      } catch (RuntimeException error) {
        warning = true;
        ModularMachine.LOGGER.warn("Committed item-bus notification failed", error);
      }
    }
    for (var lease : fluidLeases.values()) {
      try {
        lease.publish();
      } catch (RuntimeException error) {
        warning = true;
        ModularMachine.LOGGER.warn("Committed fluid-input notification failed", error);
      }
    }
    return warning ? Result.STARTED_WITH_NOTIFICATION_ERROR : Result.STARTED;
  }

  private record FluidDebit(String partId, FluidStack amount) {
  }

  private static List<FluidDebit> planFluidDebits(RecipeExecutionTicket ticket, List<FluidStack> requirements, String selector) {
    var reserved = new HashMap<String, Long>();
    var result = new ArrayList<FluidDebit>();
    for (var requirement : requirements) {
      int remaining = requirement.getAmount();
      for (var port : ticket.ports()) {
        if (port.kind() != PortKind.FLUID_INPUT
            || !port.selector().equals(selector)
            || !FluidStack.isSameFluidSameComponents(port.fluid(), requirement)) {
          continue;
        }
        long already = reserved.getOrDefault(port.partId(), 0L);
        long available = Math.max(0, port.fluidAmount() - already);
        int amount = (int) Math.min(remaining, available);
        if (amount > 0) {
          reserved.put(port.partId(), already + amount);
          result.add(new FluidDebit(port.partId(), requirement.copyWithAmount(amount)));
          remaining -= amount;
        }
        if (remaining == 0) {
          break;
        }
      }
      if (remaining > 0) {
        return null;
      }
    }
    return List.copyOf(result);
  }
}
