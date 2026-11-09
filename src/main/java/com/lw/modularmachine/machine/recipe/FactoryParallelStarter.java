package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.factory.UpgradeLifecyclePublisher;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity.StartCostLease;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.factory.FactoryParallelBatch;
import com.lw.modularmachine.machine.factory.FactoryThreadState;
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

/** Preflight boundary for a future atomic multi-run factory start. */
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

  public static Result commit(FactoryControllerBlockEntity factory, String threadId, List<ItemRecipePlan> plans) {
    var admission = admit(factory, threadId, plans, plans.size(), plans.size(), plans.size());
    if (admission.result() != Result.ADMITTED) {
      return admission.result();
    }
    if (!factory.beginRunStart(threadId)) {
      return Result.LIMIT_REACHED;
    }
    boolean committed = false;
    var leases = new ArrayList<MachinePortBlockEntity.ItemStartLease>();
    var fluidLeases = new HashMap<String, MachinePortBlockEntity.FluidStartLease>();
    var upgradeLeases = new ArrayList<StartCostLease>();
    try {
      var nativeRecipe = MachineRecipeService.definitionForStart(factory, plans.get(0));
      if (nativeRecipe.isEmpty()) {
        return Result.UNSUPPORTED;
      }
      var uniquePlans = plans.stream().distinct().toList();
      int checkBudget = MachineConfig.FACTORY_SEARCH_WORK.get();
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
      var ports = new HashMap<String, RecipeExecutionTicket.PortSnapshot>();
      for (var port : ticket.ports()) {
        ports.put(port.partId(), port);
      }
      var replacements = new HashMap<String, NonNullList<ItemStack>>();
      var fluidRequirements = nativeRecipe.orElseThrow().fluidInputs();
      for (var plan : uniquePlans) {
        if (!plan.validateForStart()
            || plan.source().inputGroup() != ticket.inputGroup()
            || plan.catalog() != plans.getFirst().catalog()
            || !plan.recipe().id().equals(plans.get(0).recipe().id())) {
          return Result.STALE;
        }
      }
      var allocated = ItemRecipePlan.combineInputs(plans);
      for (var deduction : allocated.deductions()) {
        var port = ports.get(deduction.slot().partId());
        if (port == null || port.kind() != PortKind.ITEM_INPUT) {
          return Result.STALE;
        }
        var contents = replacements.computeIfAbsent(deduction.slot().partId(), ignored -> {
          var values = port.items();
          var copy = NonNullList.withSize(values.size(), ItemStack.EMPTY);
          for (int i = 0; i < values.size(); i++) {
            copy.set(i, values.get(i));
          }
          return copy;
        });
        int slot = deduction.slot().slot();
        var before = contents.get(slot);
        if (!deduction.key().matches(before) || before.getCount() < deduction.count()) {
          return Result.STALE;
        }
        contents.set(slot, before.copyWithCount(before.getCount() - deduction.count()));
      }
      var upgrades = factory.scheduler().channels().stream().filter(thread -> thread.threadId().equals(threadId))
          .map(FactoryThreadState::upgradeSnapshot).findFirst().orElseThrow();
      var outputKeys = new HashSet<ItemResourceKey>();
      var compiled = nativeRecipe.orElseThrow()
          .compile(ResourceLocation.parse(plans.get(0).recipe().id()), factory.getLevel().registryAccess());
      var outputClaims = compiled.outputs().stream()
          .map(claim -> new RecipeCandidate.Output<>(claim.key(), Math.multiplyExact(claim.count(), plans.size())))
          .toList();
      outputClaims.forEach(claim -> outputKeys.add(claim.key()));
      var fluidOutputClaims = nativeRecipe.orElseThrow().fluidOutputs().stream()
          .map(
              fluid -> new RecipeCandidate.Output<>(
                  new FluidResourceKey(fluid, factory.getLevel().registryAccess())
                      .withSelector(nativeRecipe.orElseThrow().selectors().fluidOutput()),
                  Math.multiplyExact((long) fluid.getAmount(), plans.size())))
          .toList();
      var outputTicket = RecipeExecutionTicket.captureOutputs(factory).orElse(null);
      if (outputTicket == null) {
        return Result.STALE;
      }
      if (!outputClaims.isEmpty()) {
        var outputLayout = new ItemOutputLayout(outputTicket, outputKeys);
        var outputPlanner = RecipePlanner.prepareOutputs(
            outputLayout.snapshot,
            RecipeCandidate.outputOnly(plans.get(0).recipe().id() + "/parallel", outputClaims));
        var outputResult = outputPlanner.advance(65_536);
        if (outputResult.status() != RecipePlanner.Status.SELECTED) {
          return Result.STALE;
        }
      }
      if (!fluidOutputClaims.isEmpty()) {
        var fluidOutputPlan = new FluidOutputLayout(outputTicket, factory.getLevel().registryAccess())
            .planClaims(fluidOutputClaims);
        if (fluidOutputPlan.status() == RecipePlanner.Status.BUDGET_EXHAUSTED) {
          fluidOutputPlan.advance(65_536);
        }
        if (fluidOutputPlan.status() != RecipePlanner.Status.SELECTED) {
          return Result.STALE;
        }
      }
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
      var runs = new ArrayList<FrozenItemRun>();
      for (int index = 0; index < plans.size(); index++) {
        runs.add(
            FrozenItemRun.freeze(
                ResourceLocation.parse(plans.get(index).recipe().id()),
                nativeRecipe.orElseThrow(),
                ticket.definitionEpoch(),
                factory.getLevel().registryAccess(),
                ticket.allPorts(),
                factory.getLevel().getGameTime(),
                upgrades,
                ticket.inputGroup(),
                1,
                ticket.dynamicLengths()));
      }
      if (uniquePlans.stream().anyMatch(plan -> !plan.factoryAdmissionCurrent(factory, threadId))) {
        return Result.STALE;
      }
      startCosts.acquireInto(upgradeLeases);
      for (var port : ticket.ports()) {
        if (port.kind().resource() == PortKind.Resource.ITEM) {
          var lease = port.identity().acquireItemStart(port.revision());
          var replacement = replacements.get(port.partId());
          if (replacement != null) {
            lease.replacePrepared(replacement);
          }
          leases.add(lease);
        } else if (port.kind() == PortKind.FLUID_INPUT && !fluidRequirements.isEmpty()) {
          fluidLeases.put(port.partId(), port.identity().acquireFluidStart(port.revision()));
        }
      }
      var reservedFluids = new HashMap<String, Integer>();
      for (var requirement : fluidRequirements) {
        if (!drainFluidRequirement(
            ticket,
            fluidLeases,
            requirement,
            plans.size(),
            nativeRecipe.orElseThrow().selectors().fluidInput(),
            reservedFluids)) {
          return Result.STALE;
        }
      }
      factory.installRuns(threadId, runs);
      committed = true;
      return Result.STARTED;
    } catch (RuntimeException failure) {
      ModularMachine.LOGGER
          .warn("Factory parallel start failed at {} for thread {}", factory.getBlockPos(), threadId, failure);
      return Result.FAILED;
    } finally {
      for (var lease : leases) {
        lease.resolve(committed);
      }
      for (var lease : fluidLeases.values()) {
        lease.resolve(committed);
      }
      for (var lease : upgradeLeases) {
        lease.resolve(committed);
      }
      factory.finishRunStart(threadId, committed);
      if (committed) {
        factory.setChanged();
        FactoryUpgradeStartCosts.publish(factory, upgradeLeases);
        UpgradeLifecyclePublisher.recipeStarted(factory, threadId, factory.runSet(threadId).runs());
        leases.forEach(MachinePortBlockEntity.ItemStartLease::publish);
        fluidLeases.values().forEach(MachinePortBlockEntity.FluidStartLease::publish);
      }
    }
  }

  private static boolean drainFluidRequirement(RecipeExecutionTicket ticket, Map<String, MachinePortBlockEntity.FluidStartLease> leases, FluidStack requirement, int instances, String selector, Map<String, Integer> reserved) {
    long remaining = Math.multiplyExact((long) requirement.getAmount(), instances);
    for (var port : ticket.ports()) {
      if (port.kind() != PortKind.FLUID_INPUT
          || !port.selector().equals(selector)
          || !FluidStack.isSameFluidSameComponents(port.fluid(), requirement)) {
        continue;
      }
      int used = reserved.getOrDefault(port.partId(), 0);
      int amount = (int) Math.min(remaining, Math.max(0, port.fluid().getAmount() - used));
      if (amount > 0) {
        if (!leases.get(port.partId()).drainExact(requirement.copyWithAmount(amount))) {
          return false;
        }
        reserved.put(port.partId(), used + amount);
        remaining -= amount;
      }
      if (remaining == 0) {
        return true;
      }
    }
    return false;
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

  public static Admission admit(FactoryControllerBlockEntity factory, String threadId, List<ItemRecipePlan> plans, int inputCapacity, int outputCapacity, int startBudget) {
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
    if (plans.size() > 1 && !definition.parallelized()) {
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
    }
    try {
      return new Admission(
          Result.ADMITTED,
          FactoryParallelBatch.plan(
              Objects.requireNonNull(ResourceLocation.tryParse(recipeId)),
              definition.parallelized() ? factory.effectiveParallelLimit(threadId) : 1,
              inputCapacity,
              outputCapacity,
              startBudget,
              1));
    } catch (RuntimeException invalid) {
      return new Admission(Result.STALE, null);
    }
  }
}
