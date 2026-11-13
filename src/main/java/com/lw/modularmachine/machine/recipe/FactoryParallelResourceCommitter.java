package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.recipe.search.RecipeResourceRequirements;
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
    if (activeRuns.stream().anyMatch(run -> !PatternRunSources.available(factory, run))) {
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
    var fluidRequirements = new LinkedHashMap<FrozenItemRun, FluidStack>();
    try {
      for (var run : activeRuns) {
        if (run.energyPerTick() > 0) {
          energyRequirements.merge(run.selectors().energyInput(), run.energyPerTick(), Math::addExact);
        }
        var fluid = run.fluidPerTick(level.registryAccess());
        if (!fluid.isEmpty()) {
          fluidRequirements.put(run, fluid);
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
    var reservedFluids = new HashMap<Object, Long>();
    var scopedTickets = new ArrayList<RecipeExecutionTicket>();
    for (var requirement : fluidRequirements.entrySet()) {
      var run = requirement.getKey();
      var source = fluidTicket.forRun(run).orElse(null);
      if (source == null) {
        return Result.STALE;
      }
      scopedTickets.add(source);
      if (!RecipeResourceRequirements.has(source, PortKind.FLUID_INPUT, run.selectors().fluidPerTick())) {
        return Result.MISSING_FLUID_INPUT;
      }
      var planned = planFluids(
          source,
          List.of(requirement.getValue()),
          run.inputGroup(),
          run.selectors().fluidPerTick(),
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
        var source = chemicalTicket.forRun(run).orElse(null);
        if (source == null) {
          return Result.STALE;
        }
        scopedTickets.add(source);
        if (ChemicalTransactions.missingInputs(source, run.chemicalPerTick())) {
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
        || scopedTickets.stream().anyMatch(ticket -> !RecipeCommitGuard.validate(ticket))
        || !tickCosts.versionsCurrent()) {
      return Result.STALE;
    }
    var energyLeases = new ArrayList<MachinePortBlockEntity.EnergyStartLease>();
    var fluidLeases = new HashMap<Object, MachinePortBlockEntity.FluidStartLease>();
    var patterns = new PatternInputTransaction();
    for (var debit : fluidDebits) {
      if (debit.port().patternInput()) {
        patterns.consumeFluid(debit.port(), debit.amount());
      }
    }
    if (chemicals != null) {
      chemicals.addPatterns(patterns);
    }
    var tickCostLeases = new ArrayList<StartCostLease>();
    List<FrozenItemRun> advancedRuns = List.of();
    boolean committed = false;
    try {
      patterns.acquire();
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
        if (debit.port().patternInput()) {
          continue;
        }
        var lease = fluidLeases.computeIfAbsent(
            debit.port().physicalIdentity(),
            ignored -> debit.port().identity().acquireFluidStart(debit.port().revision(), debit.port().tankIndex()));
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
      patterns.resolve(committed);
      if (committed) {
        patterns.publish();
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
    PatternInputTransaction patterns = null;
    boolean applied = false;
    try {
      patterns = PatternInputTransaction
          .release(factory, activeRuns.stream().filter(run -> discarded.contains(run.runId())).toList());
      patterns.acquire();
      applied = factory.applyRunFailureDecisionsIfSame(threadId, set, replacements, discarded);
    } catch (RuntimeException unavailable) {
      return Result.STALE;
    } finally {
      if (patterns != null) {
        patterns.resolve(applied);
      }
    }
    if (applied) {
      patterns.publish();
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
    var required = new LinkedHashMap<FrozenItemRun, FluidStack>();
    try {
      for (var run : runs) {
        var fee = run.fluidPerTick(((ServerLevel) factory.getLevel()).registryAccess());
        if (!fee.isEmpty()) {
          required.put(run, fee);
        }
      }
    } catch (RuntimeException invalid) {
      return false;
    }
    var reserved = new HashMap<Object, Long>();
    for (var entry : required.entrySet()) {
      var run = entry.getKey();
      var source = ticket.forRun(run).orElse(null);
      if (source == null) {
        return false;
      }
      if (planFluids(
          source,
          List.of(entry.getValue()),
          run.inputGroup(),
          run.selectors().fluidPerTick(),
          reserved) == null) {
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

  private static List<FluidDebit> planFluids(RecipeExecutionTicket ticket, List<FluidStack> requirements, int group, String selector, Map<Object, Long> reserved) {
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
        long already = reserved.getOrDefault(port.physicalIdentity(), 0L);
        int take = (int) Math.min(remaining, Math.max(0, port.fluidAmount() - already));
        if (take > 0) {
          reserved.put(port.physicalIdentity(), already + take);
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
