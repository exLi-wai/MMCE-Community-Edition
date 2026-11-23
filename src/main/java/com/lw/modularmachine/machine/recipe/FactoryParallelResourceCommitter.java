package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.component.ResourceType;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceTransaction;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecution.TickStatus;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecutionRegistry;
import com.lw.modularmachine.machine.recipe.search.RecipeResourceRequirements;
import com.lw.modularmachine.machine.upgrade.UpgradeLifecyclePublisher;
import com.lw.modularmachine.api.recipe.RecipeLifecycleEvents;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity.StartCostLease;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.factory.FactoryRunSet;
import com.lw.modularmachine.api.upgrade.UpgradeRuntimeData.Phase;
import com.lw.modularmachine.machine.upgrade.UpgradeStartCosts;
import com.lw.modularmachine.machine.upgrade.UpgradeTickCosts;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/** Atomic per-tick fee transaction for every run in a parallel set. */
public final class FactoryParallelResourceCommitter {

  private record EnergyDebit(RecipePortSnapshot port, long amount) {
  }

  private FactoryParallelResourceCommitter() {
  }

  public static FactoryRunResourceResult tick(FactoryControllerBlockEntity factory, String threadId, FactoryRunSet set) {
    if (!(factory.getLevel() instanceof ServerLevel level)
        || set.runs().isEmpty()
        || UpgradeLifecyclePublisher.isDispatching(factory)) {
      return FactoryRunResourceResult.STALE;
    }
    var activeRuns = set.runs().stream().filter(run -> !run.pendingOutput()).toList();
    if (activeRuns.isEmpty()) {
      return FactoryRunResourceResult.STALE;
    }
    if (activeRuns.stream().anyMatch(run -> run.lastProgressTick() == level.getGameTime())) {
      return FactoryRunResourceResult.STALE;
    }
    if (!factory.canCommitStructure()
        || activeRuns.stream().anyMatch(run -> !run.matchesBindings(factory.registration().pattern))) {
      return FactoryRunResourceResult.STALE;
    }
    if (activeRuns.stream().anyMatch(run -> !PatternRunSources.available(factory, run))) {
      return FactoryRunResourceResult.STALE;
    }
    long instances = 0;
    try {
      for (var run : activeRuns) {
        instances = Math.addExact(instances, run.parallelInstances());
      }
    } catch (ArithmeticException overflow) {
      return FactoryRunResourceResult.UPGRADE_ERROR;
    }
    var tickCosts = factory.upgradeRuntimeWork().prepare(threadId, activeRuns);
    if (tickCosts.status() == UpgradeTickCosts.Status.INSUFFICIENT) {
      return FactoryRunResourceResult.WAITING_UPGRADE;
    }
    if (tickCosts.status() == UpgradeTickCosts.Status.PENDING) {
      return FactoryRunResourceResult.UPGRADE_PENDING;
    }
    if (tickCosts.status() != UpgradeTickCosts.Status.READY) {
      return FactoryRunResourceResult.UPGRADE_ERROR;
    }
    var energyOutputs = EnergyOutputTransactions.prepare(factory, activeRuns);
    if (energyOutputs.status() != EnergyOutputTransactions.Status.READY) {
      return switch (energyOutputs.status()) {
        case MISSING_PORT -> FactoryRunResourceResult.MISSING_ENERGY_OUTPUT;
        case BLOCKED -> FactoryRunResourceResult.OUTPUT_BLOCKED;
        default -> FactoryRunResourceResult.STALE;
      };
    }
    var energyRequirements = new LinkedHashMap<String, Long>();
    try {
      for (var run : activeRuns) {
        if (run.energyPerTick() > 0) {
          energyRequirements.merge(run.selectors().energyInput(), run.energyPerTick(), Math::addExact);
        }
      }
    } catch (ArithmeticException overflow) {
      return FactoryRunResourceResult.STALE;
    }
    var energyTicket = energyRequirements.isEmpty() ? null : energyOutputs.inputs(factory).orElse(null);
    if (!energyRequirements.isEmpty() && energyTicket == null) {
      return FactoryRunResourceResult.WAITING_ENERGY;
    }
    var energyDebits = new ArrayList<EnergyDebit>();
    for (var requirement : energyRequirements.entrySet()) {
      if (!RecipeResourceRequirements.has(energyTicket, PortKind.ENERGY_INPUT, requirement.getKey())) {
        return FactoryRunResourceResult.MISSING_ENERGY_INPUT;
      }
      var planned = planEnergy(energyTicket, requirement.getValue(), requirement.getKey());
      if (planned == null) {
        return applyFailure(
            factory,
            threadId,
            set,
            activeRuns,
            level.getGameTime(),
            FactoryRunResourceResult.WAITING_ENERGY);
      }
      energyDebits.addAll(planned);
    }
    var resourceTick = RecipeResourceExecutionRegistry.prepareTick(factory, activeRuns);
    if (resourceTick.status() == TickStatus.STALE) {
      return FactoryRunResourceResult.STALE;
    }
    if (resourceTick.status() == TickStatus.MISSING_INPUT) {
      if (resourceTick.resource() == RecipeResourceDefinitions.FLUID) {
        return FactoryRunResourceResult.MISSING_FLUID_INPUT;
      }
      return resourceTick
          .resource() == RecipeResourceDefinitions.CHEMICAL ? FactoryRunResourceResult.MISSING_CHEMICAL_INPUT : FactoryRunResourceResult.MISSING_RESOURCE_INPUT;
    }
    if (resourceTick.status() == TickStatus.MISSING_OUTPUT || resourceTick.status() == TickStatus.OUTPUT_BLOCKED) {
      return FactoryRunResourceResult.OUTPUT_BLOCKED;
    }
    if (resourceTick.status() == TickStatus.INSUFFICIENT) {
      var waiting = resourceTick
          .resource() == RecipeResourceDefinitions.CHEMICAL ? FactoryRunResourceResult.WAITING_CHEMICAL : FactoryRunResourceResult.WAITING_RESOURCE;
      if (resourceTick.resource() == RecipeResourceDefinitions.FLUID) {
        waiting = FactoryRunResourceResult.WAITING_FLUID;
      }
      return applyFailure(
          factory,
          threadId,
          set,
          activeRuns,
          level.getGameTime(),
          waiting,
          RecipeLifecycleEvents.ResourceFailure.of(resourceTick.resource().resourceType()));
    }
    var additionalResources = resourceTick.prepared();
    if (!tickCosts.current()
        || energyTicket == null && !factory.revalidateStructure(factory.registration())
        || !energyOutputs.current(energyTicket)
        || !additionalResources.current()
        || !tickCosts.versionsCurrent()) {
      return FactoryRunResourceResult.STALE;
    }
    var energyLeases = new ArrayList<MachinePortBlockEntity.EnergyStartLease>();
    var patterns = new PatternInputTransaction();
    if (additionalResources != null) {
      additionalResources.addPatterns(patterns);
    }
    var tickCostLeases = new ArrayList<StartCostLease>();
    var resourceTransaction = new RecipeResourceTransaction();
    resourceTransaction.phase().enlist(energyOutputs);
    var energyResources = resourceTransaction.phase();
    resourceTransaction.phase().track(tickCostLeases, StartCostLease::resolve);
    resourceTransaction.phase().enlist(additionalResources);
    resourceTransaction.phase().enlist(patterns);
    List<FrozenRecipeRun> advancedRuns = List.of();
    boolean committed = false;
    try {
      patterns.acquire();
      tickCosts.acquireInto(tickCostLeases);
      if (additionalResources != null) {
        additionalResources.acquire();
      }
      for (var debit : energyDebits) {
        var lease = energyResources.enlist(
            debit.port().identity().acquireEnergyStart(debit.port().revision()),
            MachinePortBlockEntity.EnergyStartLease::resolve);
        energyLeases.add(lease);
        if (!lease.extractExact(debit.amount())) {
          return FactoryRunResourceResult.STALE;
        }
      }
      energyOutputs.acquire();
      var replacements = new HashMap<UUID, FrozenRecipeRun>();
      for (var run : activeRuns) {
        var next = run.advanced(level.getGameTime());
        if (next == run) {
          return FactoryRunResourceResult.STALE;
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
      if (!factory.runState().replaceRunSetIfSame(threadId, set, replacement)) {
        return FactoryRunResourceResult.STALE;
      }
      committed = true;
    } catch (RuntimeException failure) {
      factory.runCoordinator().warnFailure(threadId, "parallel fee commit", factory.getBlockPos(), failure);
      return FactoryRunResourceResult.STALE;
    } finally {
      resourceTransaction.resolve(committed);
      if (committed) {
        energyOutputs.publish();
        patterns.publish();
        if (additionalResources != null) {
          additionalResources.publish();
        }
        factory.upgradeRuntimeWork().clear(Phase.TICK, threadId);
        UpgradeStartCosts.publish(factory, tickCostLeases);
        if (!advancedRuns.isEmpty()) {
          RecipeLifecyclePublisher.advanced(factory, threadId, activeRuns, advancedRuns);
        }
        energyLeases.forEach(FactoryParallelResourceCommitter::publishEnergy);
        factory.setChanged();
      }
    }
    return FactoryRunResourceResult.ADVANCED;
  }

  private static FactoryRunResourceResult applyFailure(FactoryControllerBlockEntity factory, String threadId, FactoryRunSet set, List<FrozenRecipeRun> activeRuns, long tick, FactoryRunResourceResult result) {
    var resource = switch (result) {
      case WAITING_ENERGY -> RecipeLifecycleEvents.ResourceFailure.ENERGY;
      case WAITING_CHEMICAL -> RecipeLifecycleEvents.ResourceFailure.CHEMICAL;
      default -> RecipeLifecycleEvents.ResourceFailure.FLUID;
    };
    return applyFailure(factory, threadId, set, activeRuns, tick, result, resource);
  }

  private static FactoryRunResourceResult applyFailure(FactoryControllerBlockEntity factory, String threadId, FactoryRunSet set, List<FrozenRecipeRun> activeRuns, long tick, FactoryRunResourceResult result, RecipeLifecycleEvents.ResourceFailure resource) {
    var replacements = new HashMap<UUID, FrozenRecipeRun>();
    var discarded = new HashSet<UUID>();
    boolean notify = RecipeLifecyclePublisher.hasListeners(RecipeLifecycleEvents.Type.RESOURCE_FAILURE);
    boolean notifyDiscard = RecipeLifecyclePublisher.hasListeners(RecipeLifecycleEvents.Type.DISCARDED);
    List<FrozenRecipeRun> before = notify ? new ArrayList<>() : List.of();
    List<FrozenRecipeRun> after = notify ? new ArrayList<>() : List.of();
    List<FrozenRecipeRun> discardedRuns = notifyDiscard ? new ArrayList<>() : List.of();
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
        || !resourceStillUnavailable(factory, activeRuns, resource)) {
      return FactoryRunResourceResult.STALE;
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
      applied = factory.runState().applyRunFailureDecisionsIfSame(threadId, set, replacements, discarded);
    } catch (RuntimeException unavailable) {
      return FactoryRunResourceResult.STALE;
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

  private static boolean resourceStillUnavailable(FactoryControllerBlockEntity factory, List<FrozenRecipeRun> runs, RecipeLifecycleEvents.ResourceFailure resource) {
    if (resource.type() != ResourceType.ENERGY) {
      return RecipeResourceExecutionRegistry.stillUnavailable(factory, runs, resource.type());
    }
    if (resource.type() == ResourceType.ENERGY) {
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

  private static void publishEnergy(MachinePortBlockEntity.EnergyStartLease lease) {
    try {
      lease.publish();
    } catch (RuntimeException ignored) {
    }
  }

}
