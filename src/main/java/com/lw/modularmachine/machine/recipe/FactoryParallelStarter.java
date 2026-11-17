package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecutionRegistry;
import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import com.lw.modularmachine.machine.recipe.search.RecipeResourceRequirements;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.factory.FactoryParallelBatch;
import com.lw.modularmachine.machine.factory.FactoryThreadState;
import com.lw.modularmachine.api.upgrade.UpgradeSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import java.util.Optional;
import com.lw.modularmachine.machine.upgrade.UpgradeRecipeCheckHandlers;

/** Admission, resource acquisition and atomic multi-run factory start. */
public final class FactoryParallelStarter {
  public enum Result {
    ADMITTED,
    EMPTY,
    LIMIT_REACHED,
    MIXED_RECIPES,
    STALE,
    UNSUPPORTED,
    STARTED,
    FAILED,
    CHECK_REJECTED,
    CHECK_PENDING,
    CHECK_ERROR,
    UPGRADE_COST_MISSING
  }

  public static Result commit(FactoryControllerBlockEntity factory, String threadId, List<RecipeStartPlan> plans) {
    return commit(factory, threadId, plans, MachineConfig.FACTORY_SEARCH_WORK.get());
  }

  public static Result commit(FactoryControllerBlockEntity factory, String threadId, List<RecipeStartPlan> plans, int workBudget) {
    if (workBudget < 0) {
      throw new IllegalArgumentException("Negative factory start budget");
    }
    var admission = admit(factory, threadId, plans, plans.size(), plans.size(), plans.size());
    if (admission.result() != Result.ADMITTED) {
      return admission.result();
    }
    if (!factory.runState().beginRunStart(threadId)) {
      return Result.LIMIT_REACHED;
    }
    boolean committed = false;
    var resources = new FactoryStartupResources(factory, threadId);
    var patterns = resources.patterns();
    RecipeResourceExecutionRegistry.Prepared additionalResources = null;
    try {
      var nativeRecipe = MachineRecipeService.definitionForStart(factory, plans.get(0));
      if (nativeRecipe.isEmpty()) {
        return Result.UNSUPPORTED;
      }
      var uniquePlans = plans.stream().distinct().toList();
      int checkBudget = workBudget;
      for (var plan : uniquePlans) {
        var checked = plan.advanceFactoryChecks(factory, threadId, checkBudget);
        checkBudget -= checked.workUsed();
        switch (checked.status()) {
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
      }
      var ticket = plans.get(0).checkedTicket().orElse(null);
      if (ticket == null || !RecipeCommitGuard.validate(ticket)) {
        return Result.STALE;
      }
      if (RecipeResourceRequirements.missing(ticket, nativeRecipe.orElseThrow()).isPresent()) {
        return Result.STALE;
      }
      if (RecipeItemPreparation.transformsOutputs(nativeRecipe.orElseThrow())
          && !plans.getFirst().hasPreparedOutputRuns(plans.size())) {
        plans.getFirst().withOutputPreparationContext(threadId);
        int preparedOutputs = plans.getFirst().prepareParallel(nativeRecipe.orElseThrow(), plans.size(), checkBudget);
        checkBudget = plans.getFirst().parallelBudgetRemaining();
        if (preparedOutputs < 0) {
          return Result.CHECK_PENDING;
        }
        if (preparedOutputs != plans.size()) {
          return Result.CHECK_REJECTED;
        }
      }
      var outputCheck = plans.getFirst()
          .prepareFactoryOutputs(nativeRecipe.orElseThrow(), ticket, plans.size(), checkBudget);
      checkBudget -= outputCheck.workUsed();
      if (outputCheck.status() == RecipeResourcePlanning.Status.PENDING) {
        return Result.CHECK_PENDING;
      }
      if (outputCheck.status() != RecipeResourcePlanning.Status.READY) {
        return Result.STALE;
      }
      var ports = indexPorts(ticket);
      var replacements = new HashMap<String, NonNullList<ItemStack>>();
      var fluidRequirements = nativeRecipe.orElseThrow().fluidInputs();
      if (!sameStartScope(uniquePlans, plans.getFirst(), ticket)) {
        return Result.STALE;
      }
      if (!prepareItemDeductions(plans, resources, ports, replacements)) {
        return Result.STALE;
      }
      var upgrades = factory.scheduler().channels().stream().filter(thread -> thread.threadId().equals(threadId))
          .map(FactoryThreadState::upgradeSnapshot).findFirst().orElseThrow();
      var startCosts = plans.getFirst().prepareFactoryStartup(factory, threadId, plans.size());
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
      var runs = prepareRuns(factory, threadId, plans, nativeRecipe.orElseThrow(), ticket, upgrades);
      if (!RecipeItemPayments.apply(
          ticket,
          nativeRecipe.orElseThrow(),
          plans.size(),
          false,
          plans.getFirst().inputChanceSeed(),
          replacements)) {
        return Result.STALE;
      }
      additionalResources = RecipeResourceExecutionRegistry.preparePaidStart(ticket, runs);
      if (additionalResources == null) {
        return Result.STALE;
      }
      resources.enlistAdditional(additionalResources);
      if (uniquePlans.stream().anyMatch(plan -> !plan.factoryAdmissionCurrent(factory, threadId))) {
        return Result.STALE;
      }
      var inputCheck = RecipeItemPreparation
          .checkInputs(nativeRecipe.orElseThrow(), ticket, Optional.of(threadId), plans.size(), runs.getFirst());
      if (runs.stream().anyMatch(run -> !run.preparedItemCallbacksCurrent())
          || !plans.getFirst().preparedOutputsCurrent(plans.size())
          || !plans.getFirst().factoryOutputsCurrent(nativeRecipe.orElseThrow(), plans.size())
          || uniquePlans.stream()
              .anyMatch(plan -> !plan.factoryAdmissionCurrent(factory, threadId) || !plan.catalog().isCurrent())
          || !startCosts.current()) {
        return Result.STALE;
      }
      if (inputCheck != RecipeItemCallbackService.InputCheck.ALLOWED) {
        return switch (inputCheck) {
          case REJECTED -> Result.CHECK_REJECTED;
          case ERROR -> Result.CHECK_ERROR;
          default -> Result.STALE;
        };
      }
      if (!factory
          .recipeThreadAllowed(threadId, runs.getFirst().recipeId(), nativeRecipe.orElseThrow().executionOptions())) {
        return Result.STALE;
      }
      var fluidDebits = planFluidDebits(
          ticket,
          fluidRequirements,
          plans.size(),
          nativeRecipe.orElseThrow().selectors().fluidInput());
      if (fluidDebits == null) {
        return Result.STALE;
      }
      fluidDebits = planFluidDebits(
          ticket,
          runs.stream().flatMap(run -> run.fluidInputClaims(factory.getLevel().registryAccess()).stream()).toList(),
          1,
          nativeRecipe.orElseThrow().selectors().fluidInput());
      if (fluidDebits == null) {
        return Result.STALE;
      }
      patterns.bindStart(ticket, runs);
      for (var debit : fluidDebits) {
        if (debit.port().patternInput()) {
          patterns.consumeFluid(debit.port(), debit.amount());
        }
      }
      additionalResources.addPatterns(patterns);
      if (!additionalResources.current()) {
        return Result.STALE;
      }
      if (!energyTotalsFit(runs)) {
        return Result.LIMIT_REACHED;
      }
      patterns.acquire();
      startCosts.acquireInto(resources.upgradeLeases());
      additionalResources.acquire();
      if (runs.stream().anyMatch(run -> !run.preparedItemCallbacksCurrent())) {
        return Result.STALE;
      }
      resources.acquire(ticket, replacements, !fluidRequirements.isEmpty());
      for (var debit : fluidDebits) {
        if (!debit.port().patternInput() && !resources.drainFluid(debit.port().partId(), debit.amount())) {
          return Result.STALE;
        }
      }
      factory.runState().installRuns(threadId, runs);
      committed = true;
      return Result.STARTED;
    } catch (UpgradeRecipeCheckHandlers.StaleException stale) {
      return Result.STALE;
    } catch (RuntimeException failure) {
      ModularMachine.LOGGER
          .warn("Factory parallel start failed at {} for thread {}", factory.getBlockPos(), threadId, failure);
      return Result.FAILED;
    } finally {
      resources.resolve(committed);
      if (committed) {
        resources.publishCommitted();
      }
    }
  }

  private static Map<String, RecipePortSnapshot> indexPorts(RecipeExecutionTicket ticket) {
    var ports = new HashMap<String, RecipePortSnapshot>();
    for (var port : ticket.ports()) {
      ports.put(port.partId(), port);
    }

    return ports;
  }

  /**
   * Recheck the actual frozen batch before acquiring any startup resource lease.
   */
  static boolean energyTotalsFit(List<FrozenRecipeRun> runs) {
    var inputs = new HashMap<String, Long>();
    var outputs = new HashMap<String, Long>();
    try {
      for (var run : runs) {
        if (run.energyPerTick() > 0) {
          inputs.merge(run.selectors().energyInput(), run.energyPerTick(), Math::addExact);
        }
        if (run.energyOutputPerTick() > 0) {
          outputs.merge(run.selectors().energyOutput(), run.energyOutputPerTick(), Math::addExact);
        }
      }
    } catch (ArithmeticException overflow) {
      return false;
    }

    return true;
  }

  private static boolean sameStartScope(List<RecipeStartPlan> uniquePlans, RecipeStartPlan first, RecipeExecutionTicket ticket) {
    for (var plan : uniquePlans) {
      if (!plan.validateForStart()
          || plan.source().inputGroup() != ticket.inputGroup()
          || !RecipeStartPlan.samePatternScope(plan.source(), ticket)
          || plan.catalog() != first.catalog()
          || !plan.recipe().id().equals(first.recipe().id())) {
        return false;
      }
    }

    return true;
  }

  /**
   * Prepares item replacements and pattern claims without acquiring resources.
   */
  private static boolean prepareItemDeductions(List<RecipeStartPlan> plans, FactoryStartupResources resources, Map<String, RecipePortSnapshot> ports, Map<String, NonNullList<ItemStack>> replacements) {
    var allocated = RecipeStartPlan.combineInputs(plans);
    for (var deduction : allocated.deductions()) {
      var target = resources.prepareDeduction(deduction, ports, replacements);
      if (!target.accepted()) {
        return false;
      }
      var contents = target.contents();
      if (contents == null) {
        continue;
      }
      int slot = deduction.slot().slot();
      var before = contents.get(slot);
      if (!deduction.key().matches(before) || before.getCount() < deduction.count()) {
        return false;
      }
      contents.set(slot, before.copyWithCount(Math.toIntExact(before.getCount() - deduction.count())));
    }

    return true;
  }

  private static List<FrozenRecipeRun> prepareRuns(FactoryControllerBlockEntity factory, String threadId, List<RecipeStartPlan> plans, MachineRecipe recipe, RecipeExecutionTicket ticket, UpgradeSnapshot upgrades) {
    var runs = new ArrayList<FrozenRecipeRun>();
    var preparedRuns = plans.getFirst().preparedOutputRuns(plans.size());
    for (int index = 0; index < plans.size(); index++) {
      runs.add(
          !preparedRuns.isEmpty() ? preparedRuns.get(index)
              .atStartTick(factory.getLevel().getGameTime()) : FrozenRecipeFactory.freeze(
                  ResourceLocation.parse(plans.get(index).recipe().id()),
                  recipe,
                  ticket.definitionEpoch(),
                  factory.getLevel().registryAccess(),
                  ticket.allPorts(),
                  factory.getLevel().getGameTime(),
                  upgrades,
                  ticket.inputGroup(),
                  1,
                  ticket.dynamicLengths(),
                  RecipeItemPreparation.context(
                      ticket,
                      ResourceLocation.parse(plans.get(index).recipe().id()),
                      Optional.of(threadId),
                      1)));
    }

    return runs;
  }

  private record FluidDebit(RecipePortSnapshot port, FluidStack amount) {
  }

  private static List<FluidDebit> planFluidDebits(RecipeExecutionTicket ticket, List<FluidStack> requirements, int instances, String selector) {
    var result = new ArrayList<FluidDebit>();
    var reserved = new HashMap<String, Long>();
    for (var requirement : requirements) {
      long remaining = Math.multiplyExact((long) requirement.getAmount(), instances);
      for (var port : ticket.ports()) {
        if (port.kind() != PortKind.FLUID_INPUT
            || !port.selector().equals(selector)
            || !FluidStack.isSameFluidSameComponents(port.fluid(), requirement)) {
          continue;
        }
        long used = reserved.getOrDefault(port.partId(), 0L);
        long available = Math.max(0, port.fluidAmount() - used);
        while (remaining > 0 && available > 0) {
          int amount = (int) Math.min(Integer.MAX_VALUE, Math.min(remaining, available));
          result.add(new FluidDebit(port, requirement.copyWithAmount(amount)));
          used += amount;
          reserved.put(port.partId(), used);
          remaining -= amount;
          available -= amount;
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

  public record Admission(Result result, FactoryParallelBatch batch) {
    public Admission {
      if (result == Result.ADMITTED && batch == null || result != Result.ADMITTED && batch != null) {
        throw new IllegalArgumentException("Invalid factory parallel admission");
      }
    }
  }

  private FactoryParallelStarter() {
  }

  public static Admission admit(FactoryControllerBlockEntity factory, String threadId, List<RecipeStartPlan> plans, int inputCapacity, int outputCapacity, int startBudget) {
    Objects.requireNonNull(factory, "factory");
    Objects.requireNonNull(plans, "plans");
    var thread = factory.scheduler().channels().stream().filter(candidate -> candidate.threadId().equals(threadId))
        .findFirst().orElse(null);
    if (thread == null || plans.isEmpty()) {
      return new Admission(plans.isEmpty() ? Result.EMPTY : Result.STALE, null);
    }
    if (plans.size() > factory.effectiveParallelLimit(threadId)) {
      return new Admission(Result.LIMIT_REACHED, null);
    }
    var first = plans.get(0);
    if (first == null) {
      return new Admission(Result.STALE, null);
    }
    var definition = MachineRecipeService.definitionForStart(factory, first).orElse(null);
    if (definition == null) {
      return new Admission(Result.STALE, null);
    }
    if (plans.size() > first.maxRepeatableInputs() || plans.size() > 1 && !definition.parallelized()) {
      return new Admission(Result.LIMIT_REACHED, null);
    }
    int energyLimit = FrozenRecipeFactory.factoryTickLimit(
        factory.effectiveParallelLimit(threadId),
        definition.energyPerTick(),
        definition.energyOutputPerTick(),
        thread.upgradeSnapshot());
    if (plans.size() > energyLimit) {
      return new Admission(Result.LIMIT_REACHED, null);
    }
    var recipeId = first.recipe().id();
    for (var plan : plans.stream().distinct().toList()) {
      if (plan == null || !recipeId.equals(plan.recipe().id())) {
        return new Admission(Result.MIXED_RECIPES, null);
      }
      if (!plan.validateForStart()) {
        return new Admission(Result.STALE, null);
      }
      if (RecipeResourceRequirements.missing(plan.source(), definition).isPresent()) {
        return new Admission(Result.STALE, null);
      }
    }
    try {
      return new Admission(
          Result.ADMITTED,
          FactoryParallelBatch.plan(
              Objects.requireNonNull(ResourceLocation.tryParse(recipeId)),
              definition.parallelized() ? energyLimit : 1,
              inputCapacity,
              outputCapacity,
              startBudget,
              1));
    } catch (RuntimeException invalid) {
      return new Admission(Result.STALE, null);
    }
  }
}
