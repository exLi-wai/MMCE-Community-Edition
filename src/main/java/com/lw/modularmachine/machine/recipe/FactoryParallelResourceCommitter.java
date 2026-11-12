package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.factory.UpgradeLifecyclePublisher;
import com.lw.modularmachine.api.recipe.RecipeLifecycleEvents;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity.StartCostLease;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.factory.FactoryRunSet;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeRuntimeData.Phase;
import com.lw.modularmachine.machine.factory.FactoryUpgradeStartCosts;
import com.lw.modularmachine.machine.factory.FactoryUpgradeTickCosts;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.fluids.FluidStack;

/** Atomic per-tick fee transaction for every run in a parallel set. */
public final class FactoryParallelResourceCommitter {
  private record FluidInputKey(int group, String selector) {
  }

  public enum Result {
    ADVANCED,
    WAITING_ENERGY,
    WAITING_FLUID,
    WAITING_CHEMICAL,
    WAITING_UPGRADE,
    UPGRADE_ERROR,
    STALE,
    UPGRADE_PENDING,
    MISSING_ENERGY_INPUT,
    MISSING_FLUID_INPUT,
    MISSING_CHEMICAL_INPUT
  }

  private record EnergyDebit(RecipeExecutionTicket.PortSnapshot port, long amount) {
  }

  private record FluidDebit(RecipeExecutionTicket.PortSnapshot port, FluidStack amount) {
  }

  private FactoryParallelResourceCommitter() {
  }

  public static Result tick(FactoryControllerBlockEntity factory, String threadId, FactoryRunSet set) {
    if (!(factory.getLevel() instanceof ServerLevel level)
        || set.runs().isEmpty()
        || UpgradeLifecyclePublisher.isDispatching(factory)) {
      return Result.STALE;
    }
    var activeRuns = set.runs().stream().filter(run -> !run.pendingOutput()).toList();
    if (activeRuns.isEmpty()) {
      return Result.STALE;
    }
    if (activeRuns.stream().anyMatch(run -> run.lastProgressTick() == level.getGameTime())) {
      return Result.STALE;
    }
    if (!factory.canCommitStructure()
        || activeRuns.stream().anyMatch(run -> !run.matchesBindings(factory.registration().pattern.definition))) {
      return Result.STALE;
    }
    long instances = 0;
    try {
      for (var run : activeRuns) {
        instances = Math.addExact(instances, run.parallelInstances());
      }
    } catch (ArithmeticException overflow) {
      return Result.UPGRADE_ERROR;
    }
    var tickCosts = factory.upgradeRuntimeWork().prepare(threadId, activeRuns);
    if (tickCosts.status() == FactoryUpgradeTickCosts.Status.INSUFFICIENT) {
      return Result.WAITING_UPGRADE;
    }
    if (tickCosts.status() == FactoryUpgradeTickCosts.Status.PENDING) {
      return Result.UPGRADE_PENDING;
    }
    if (tickCosts.status() != FactoryUpgradeTickCosts.Status.READY) {
      return Result.UPGRADE_ERROR;
    }
    var energyRequirements = new LinkedHashMap<String, Long>();
    var fluidRequirements = new LinkedHashMap<FluidInputKey, List<FluidStack>>();
    try {
      for (var run : activeRuns) {
        if (run.energyPerTick() > 0) {
          energyRequirements.merge(run.selectors().energyInput(), run.energyPerTick(), Math::addExact);
        }
        var fluid = run.fluidPerTick(level.registryAccess());
        if (!fluid.isEmpty()) {
          fluidRequirements.computeIfAbsent(
              new FluidInputKey(run.inputGroup(), run.selectors().fluidPerTick()),
              ignored -> new ArrayList<>()).add(fluid);
        }
      }
    } catch (ArithmeticException overflow) {
      return Result.STALE;
    }
    var energyTicket = energyRequirements.isEmpty() ? null : RecipeExecutionTicket.captureEnergyInputs(factory)
        .orElse(null);
    if (!energyRequirements.isEmpty() && energyTicket == null) {
      return Result.WAITING_ENERGY;
    }
    var fluidTicket = fluidRequirements.isEmpty() ? null : RecipeExecutionTicket.captureFluidInputs(factory)
        .orElse(null);
    if (!fluidRequirements.isEmpty() && fluidTicket == null) {
      return Result.WAITING_FLUID;
    }
    if (fluidTicket != null && activeRuns.stream().anyMatch(run -> !run.matchesInputGroups(fluidTicket))) {
      return Result.STALE;
    }
    var energyDebits = new ArrayList<EnergyDebit>();
    for (var requirement : energyRequirements.entrySet()) {
      if (!RecipeResourceRequirements.has(energyTicket, PortKind.ENERGY_INPUT, requirement.getKey())) {
        return Result.MISSING_ENERGY_INPUT;
      }
      var planned = planEnergy(energyTicket, requirement.getValue(), requirement.getKey());
      if (planned == null) {
        return applyFailure(factory, threadId, set, activeRuns, level.getGameTime(), Result.WAITING_ENERGY);
      }
      energyDebits.addAll(planned);
    }
    var fluidDebits = new ArrayList<FluidDebit>();
    var reservedFluids = new HashMap<String, Integer>();
    for (var requirement : fluidRequirements.entrySet()) {
      if (!RecipeResourceRequirements.has(
          fluidTicket.withInputGroup(requirement.getKey().group()),
          PortKind.FLUID_INPUT,
          requirement.getKey().selector())) {
        return Result.MISSING_FLUID_INPUT;
      }
      var planned = planFluids(
          fluidTicket,
          requirement.getValue(),
          requirement.getKey().group(),
          requirement.getKey().selector(),
          reservedFluids);
      if (planned == null) {
        return applyFailure(factory, threadId, set, activeRuns, level.getGameTime(), Result.WAITING_FLUID);
      }
      fluidDebits.addAll(planned);
    }
    boolean hasChemicals = activeRuns.stream().anyMatch(run -> !run.chemicalPerTick().isEmpty());
    var chemicalTicket = hasChemicals ? RecipeExecutionTicket.captureChemicalInputs(factory).orElse(null) : null;
    ChemicalTransactions.Batch chemicals = null;
    if (hasChemicals) {
      if (chemicalTicket == null || activeRuns.stream().anyMatch(run -> !run.matchesInputGroups(chemicalTicket))) {
        return Result.STALE;
      }
      for (var run : activeRuns) {
        if (ChemicalTransactions
            .missingInputs(chemicalTicket.withInputGroup(run.inputGroup()), run.chemicalPerTick())) {
          return Result.MISSING_CHEMICAL_INPUT;
        }
      }
      chemicals = ChemicalTransactions.fees(chemicalTicket, activeRuns);
      if (chemicals == null) {
        return applyFailure(factory, threadId, set, activeRuns, level.getGameTime(), Result.WAITING_CHEMICAL);
      }
    }
    if (!tickCosts.current()
        || energyTicket == null && fluidTicket == null && !factory.revalidateStructure(factory.registration())
        || energyTicket != null && !RecipeCommitGuard.validate(energyTicket)
        || fluidTicket != null && !RecipeCommitGuard.validate(fluidTicket)
        || chemicalTicket != null && !RecipeCommitGuard.validate(chemicalTicket)
        || !tickCosts.versionsCurrent()) {
      return Result.STALE;
    }
    var energyLeases = new ArrayList<MachinePortBlockEntity.EnergyStartLease>();
    var fluidLeases = new HashMap<String, MachinePortBlockEntity.FluidStartLease>();
    var tickCostLeases = new ArrayList<StartCostLease>();
    List<FrozenItemRun> advancedRuns = List.of();
    boolean committed = false;
    try {
      tickCosts.acquireInto(tickCostLeases);
      if (chemicals != null) {
        chemicals.acquire();
      }
      for (var debit : energyDebits) {
        var lease = debit.port().identity().acquireEnergyStart(debit.port().revision());
        energyLeases.add(lease);
        if (!lease.extractExact(debit.amount())) {
          return Result.STALE;
        }
      }
      for (var debit : fluidDebits) {
        var lease = fluidLeases.computeIfAbsent(
            debit.port().partId(),
            ignored -> debit.port().identity().acquireFluidStart(debit.port().revision()));
        if (!lease.drainExact(debit.amount())) {
          return Result.STALE;
        }
      }
      var replacements = new HashMap<UUID, FrozenItemRun>();
      for (var run : activeRuns) {
        var next = run.advanced(level.getGameTime());
        if (next == run) {
          return Result.STALE;
        }
        if (next.progress() == next.duration()) {
          next = next.finished();
        }
        replacements.put(run.runId(), next);
      }
      if (RecipeLifecyclePublisher.hasListeners(RecipeLifecycleEvents.Type.TICK)) {
        advancedRuns = activeRuns.stream().map(run -> replacements.get(run.runId())).toList();
      }
      var replacement = set.replaceAll(replacements);
      if (!factory.replaceRunSetIfSame(threadId, set, replacement)) {
        return Result.STALE;
      }
      committed = true;
    } catch (RuntimeException failure) {
      ModularMachine.LOGGER.warn("Parallel factory fee commit failed at {}", factory.getBlockPos(), failure);
      return Result.STALE;
    } finally {
      for (var lease : energyLeases) {
        lease.resolve(committed);
      }
      for (var lease : fluidLeases.values()) {
        lease.resolve(committed);
      }
      for (var lease : tickCostLeases) {
        lease.resolve(committed);
      }
      if (chemicals != null) {
        chemicals.resolve(committed);
      }
      if (committed) {
        if (chemicals != null) {
          chemicals.publish();
        }
        factory.upgradeRuntimeWork().clear(Phase.TICK, threadId);
        FactoryUpgradeStartCosts.publish(factory, tickCostLeases);
        if (!advancedRuns.isEmpty()) {
          RecipeLifecyclePublisher.advanced(factory, threadId, activeRuns, advancedRuns);
        }
        energyLeases.forEach(FactoryParallelResourceCommitter::publishEnergy);
        fluidLeases.values().forEach(FactoryParallelResourceCommitter::publishFluid);
        factory.setChanged();
      }
    }
    return Result.ADVANCED;
  }

  private static Result applyFailure(FactoryControllerBlockEntity factory, String threadId, FactoryRunSet set, List<FrozenItemRun> activeRuns, long tick, Result result) {
    var replacements = new HashMap<UUID, FrozenItemRun>();
    var discarded = new HashSet<UUID>();
    var resource = switch (result) {
      case WAITING_ENERGY -> RecipeLifecycleEvents.ResourceFailure.ENERGY;
      case WAITING_CHEMICAL -> RecipeLifecycleEvents.ResourceFailure.CHEMICAL;
      default -> RecipeLifecycleEvents.ResourceFailure.FLUID;
    };
    boolean notify = RecipeLifecyclePublisher.hasListeners(RecipeLifecycleEvents.Type.RESOURCE_FAILURE);
    boolean notifyDiscard = RecipeLifecyclePublisher.hasListeners(RecipeLifecycleEvents.Type.DISCARDED);
    List<FrozenItemRun> before = notify ? new ArrayList<>() : List.of();
    List<FrozenItemRun> after = notify ? new ArrayList<>() : List.of();
    List<FrozenItemRun> discardedRuns = notifyDiscard ? new ArrayList<>() : List.of();
    for (var run : activeRuns) {
      var decision = RecipeLifecyclePublisher.decideResourceFailure(factory, Optional.of(threadId), run, resource);
      if (decision == RecipeLifecycleEvents.FailureDecision.DISCARD) {
        discarded.add(run.runId());
        if (notifyDiscard) {
          discardedRuns.add(run);
        }
        continue;
      }
      var adjusted = RecipeLifecyclePublisher.applyFailureDecision(run, tick, decision);
      if (adjusted != run) {
        replacements.put(run.runId(), adjusted);
        if (notify) {
          before.add(run);
          after.add(adjusted);
        }
      }
    }
    if (factory.runSet(threadId) != set
        || !factory.canCommitStructure()
        || !resourceStillUnavailable(factory, activeRuns, result)) {
      return Result.STALE;
    }
    if (replacements.isEmpty() && discarded.isEmpty()) {
      return result;
    }
    if (factory.applyRunFailureDecisionsIfSame(threadId, set, replacements, discarded)) {
      factory.setChanged();
      if (notify && !before.isEmpty()) {
        RecipeLifecyclePublisher.resourceFailed(factory, threadId, before, after, resource);
      }
      if (notifyDiscard && !discardedRuns.isEmpty()) {
        RecipeLifecyclePublisher.resourceDiscarded(factory, threadId, discardedRuns, resource);
      }
    }
    return result;
  }

  private static boolean resourceStillUnavailable(FactoryControllerBlockEntity factory, List<FrozenItemRun> runs, Result result) {
    if (result == Result.WAITING_CHEMICAL) {
      return ChemicalTransactions.stillUnavailable(factory, runs);
    }
    if (result == Result.WAITING_ENERGY) {
      var ticket = RecipeExecutionTicket.captureEnergyInputs(factory).orElse(null);
      if (ticket == null) {
        return false;
      }
      var required = new LinkedHashMap<String, Long>();
      try {
        for (var run : runs) {
          if (run.energyPerTick() > 0) {
            required.merge(run.selectors().energyInput(), run.energyPerTick(), Math::addExact);
          }
        }
      } catch (ArithmeticException overflow) {
        return false;
      }
      return required.entrySet().stream()
          .anyMatch(entry -> planEnergy(ticket, entry.getValue(), entry.getKey()) == null);
    }

    var ticket = RecipeExecutionTicket.captureFluidInputs(factory).orElse(null);
    if (ticket == null || runs.stream().anyMatch(run -> !run.matchesInputGroups(ticket))) {
      return false;
    }
    var required = new LinkedHashMap<FluidInputKey, List<FluidStack>>();
    try {
      for (var run : runs) {
        var fee = run.fluidPerTick(((ServerLevel) factory.getLevel()).registryAccess());
        if (!fee.isEmpty()) {
          required.computeIfAbsent(
              new FluidInputKey(run.inputGroup(), run.selectors().fluidPerTick()),
              ignored -> new ArrayList<>()).add(fee);
        }
      }
    } catch (RuntimeException invalid) {
      return false;
    }
    var reserved = new HashMap<String, Integer>();
    for (var entry : required.entrySet()) {
      if (planFluids(ticket, entry.getValue(), entry.getKey().group(), entry.getKey().selector(), reserved) == null) {
        return true;
      }
    }

    return false;
  }

  private static List<EnergyDebit> planEnergy(RecipeExecutionTicket ticket, long amount, String selector) {
    long remaining = amount;
    var result = new ArrayList<EnergyDebit>();
    for (var port : ticket.ports()) {
      if (port.kind() != PortKind.ENERGY_INPUT || !port.selector().equals(selector)) {
        continue;
      }
      long take = Math.min(remaining, Math.min(port.energy(), port.energyExtractable()));
      if (take > 0) {
        result.add(new EnergyDebit(port, take));
        remaining -= take;
      }
      if (remaining == 0) {
        return List.copyOf(result);
      }
    }
    return null;
  }

  private static List<FluidDebit> planFluids(RecipeExecutionTicket ticket, List<FluidStack> requirements, int group, String selector, Map<String, Integer> reserved) {
    var result = new ArrayList<FluidDebit>();
    for (var requirement : requirements) {
      int remaining = requirement.getAmount();
      for (var port : ticket.withInputGroup(group).ports()) {
        if (port.kind() != PortKind.FLUID_INPUT
            || !port.selector().equals(selector)
            || !InputGroup.includes(group, port.inputGroup())
            || !FluidStack.isSameFluidSameComponents(port.fluid(), requirement)) {
          continue;
        }
        int already = reserved.getOrDefault(port.partId(), 0);
        int take = Math.min(remaining, Math.max(0, port.fluid().getAmount() - already));
        if (take > 0) {
          reserved.put(port.partId(), already + take);
          result.add(new FluidDebit(port, requirement.copyWithAmount(take)));
          remaining -= take;
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

  private static void publishEnergy(MachinePortBlockEntity.EnergyStartLease lease) {
    try {
      lease.publish();
    } catch (RuntimeException ignored) {
    }
  }

  private static void publishFluid(MachinePortBlockEntity.FluidStartLease lease) {
    try {
      lease.publish();
    } catch (RuntimeException ignored) {
    }
  }
}
