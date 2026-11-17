package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.component.ResourceType;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceTransaction;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecution.TickStatus;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecutionRegistry;
import com.lw.modularmachine.machine.recipe.search.RecipeResourceRequirements;
import com.lw.modularmachine.machine.upgrade.UpgradeLifecyclePublisher;
import com.lw.modularmachine.api.recipe.RecipeLifecycleEvents;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity.StartCostLease;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.api.upgrade.UpgradeRuntimeData.Phase;
import com.lw.modularmachine.machine.upgrade.UpgradeStartCosts;
import com.lw.modularmachine.machine.upgrade.UpgradeTickCosts;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;

/** Atomic FE/t and fluid/t fee transaction for one factory run. */
public final class FactoryRunResourceCommitter {

  private FactoryRunResourceCommitter() {
  }

  public static FactoryRunResourceResult tick(FactoryControllerBlockEntity factory, String threadId, FrozenRecipeRun run) {
    if (!(factory.getLevel() instanceof ServerLevel level)
        || run.pendingOutput()
        || UpgradeLifecyclePublisher.isDispatching(factory)) {
      return FactoryRunResourceResult.STALE;
    }
    if (!factory.canCommitStructure() || !run.matchesBindings(factory.registration().pattern.definition)) {
      return FactoryRunResourceResult.STALE;
    }
    if (run.lastProgressTick() == level.getGameTime()) {
      return FactoryRunResourceResult.STALE;
    }
    if (!PatternRunSources.available(factory, run)) {
      return FactoryRunResourceResult.STALE;
    }
    var tickCosts = factory.upgradeRuntimeWork().prepare(threadId, List.of(run));
    if (tickCosts.status() == UpgradeTickCosts.Status.INSUFFICIENT) {
      return FactoryRunResourceResult.WAITING_UPGRADE;
    }
    if (tickCosts.status() == UpgradeTickCosts.Status.PENDING) {
      return FactoryRunResourceResult.UPGRADE_PENDING;
    }
    if (tickCosts.status() != UpgradeTickCosts.Status.READY) {
      return FactoryRunResourceResult.UPGRADE_ERROR;
    }
    var energyOutputs = EnergyOutputTransactions.prepare(factory, List.of(run));
    if (energyOutputs.status() != EnergyOutputTransactions.Status.READY) {
      return switch (energyOutputs.status()) {
        case MISSING_PORT -> FactoryRunResourceResult.MISSING_ENERGY_OUTPUT;
        case BLOCKED -> FactoryRunResourceResult.OUTPUT_BLOCKED;
        default -> FactoryRunResourceResult.STALE;
      };
    }
    var beforeTick = run;
    RecipeExecutionTicket energyTicket = null;
    List<EnergyDebit> energyDebits = List.of();
    if (run.energyPerTick() > 0) {
      energyTicket = RecipeExecutionTicket.captureEnergyInputs(factory).orElse(null);
      if (energyTicket == null) {
        return FactoryRunResourceResult.WAITING_ENERGY;
      }
      if (!RecipeResourceRequirements.has(energyTicket, PortKind.ENERGY_INPUT, run.selectors().energyInput())) {
        return FactoryRunResourceResult.MISSING_ENERGY_INPUT;
      }
      energyDebits = planEnergyDebits(energyTicket, run.energyPerTick(), run.selectors().energyInput());
      if (energyDebits == null) {
        return applyResourceFailure(
            factory,
            threadId,
            run,
            level.getGameTime(),
            FactoryRunResourceResult.WAITING_ENERGY);
      }
    }
    var resourceTick = RecipeResourceExecutionRegistry.prepareTick(factory, List.of(run));
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
      return applyResourceFailure(
          factory,
          threadId,
          run,
          level.getGameTime(),
          waiting,
          RecipeLifecycleEvents.ResourceFailure.of(resourceTick.resource().resourceType()));
    }
    var additionalResources = resourceTick.prepared();
    if (energyTicket == null
        && !additionalResources.hasWork()
        && !tickCosts.hasRuntimeWork()
        && run.energyOutputPerTick() == 0) {
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
    boolean committed = false;
    try {
      if (!tickCosts.current()
          || !energyOutputs.current()
          || energyTicket == null && !factory.revalidateStructure(factory.registration())
          || energyTicket != null && !RecipeCommitGuard.validate(energyTicket)
          || !additionalResources.current()
          || !tickCosts.versionsCurrent()) {
        return FactoryRunResourceResult.STALE;
      }
      patterns.acquire();
      tickCosts.acquireInto(tickCostLeases);
      if (additionalResources != null) {
        additionalResources.acquire();
      }
      for (var debit : energyDebits) {
        energyLeases.add(
            energyResources.enlist(
                debit.port().identity().acquireEnergyStart(debit.port().revision()),
                MachinePortBlockEntity.EnergyStartLease::resolve));
      }
      for (int index = 0; index < energyDebits.size(); index++) {
        var debit = energyDebits.get(index);
        if (!energyLeases.get(index).extractExact(debit.amount())) {
          return FactoryRunResourceResult.STALE;
        }
      }
      energyOutputs.acquire();
      var advanced = run.advanced(level.getGameTime());
      if (advanced == run) {
        return FactoryRunResourceResult.STALE;
      }
      if (advanced.progress() == advanced.duration()) {
        advanced = advanced.finished();
      }
      if (!factory.runState().replaceRunIfSame(threadId, run, advanced)) {
        return FactoryRunResourceResult.STALE;
      }
      committed = true;
      run = advanced;
      return FactoryRunResourceResult.ADVANCED;
    } catch (RuntimeException failure) {
      ModularMachine.LOGGER.warn("Factory run resource commit failed at {}", factory.getBlockPos(), failure);
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
        if (RecipeLifecyclePublisher.hasListeners(RecipeLifecycleEvents.Type.TICK)) {
          RecipeLifecyclePublisher.advanced(factory, threadId, List.of(beforeTick), List.of(run));
        }
        RecipeResourceTransaction.publishAll(
            energyLeases,
            MachinePortBlockEntity.EnergyStartLease::publish,
            failure -> ModularMachine.LOGGER.warn("Factory energy notification failed", failure));
        factory.setChanged();
      }
    }
  }

  private static FactoryRunResourceResult applyResourceFailure(FactoryControllerBlockEntity factory, String threadId, FrozenRecipeRun run, long tick, FactoryRunResourceResult waiting) {
    var resource = switch (waiting) {
      case WAITING_ENERGY -> RecipeLifecycleEvents.ResourceFailure.ENERGY;
      case WAITING_CHEMICAL -> RecipeLifecycleEvents.ResourceFailure.CHEMICAL;
      default -> RecipeLifecycleEvents.ResourceFailure.FLUID;
    };
    return applyResourceFailure(factory, threadId, run, tick, waiting, resource);
  }

  private static FactoryRunResourceResult applyResourceFailure(FactoryControllerBlockEntity factory, String threadId, FrozenRecipeRun run, long tick, FactoryRunResourceResult waiting, RecipeLifecycleEvents.ResourceFailure resource) {
    var decision = RecipeLifecyclePublisher.decideResourceFailure(factory, Optional.of(threadId), run, resource);
    if (!factory.canCommitStructure()
        || factory.runSet(threadId).find(run.runId()).orElse(null) != run
        || !stillUnavailable(factory, run, resource)) {
      return FactoryRunResourceResult.STALE;
    }
    if (decision == RecipeLifecycleEvents.FailureDecision.DISCARD) {
      PatternInputTransaction patterns = null;
      boolean removed = false;
      try {
        patterns = PatternInputTransaction.release(factory, List.of(run));
        patterns.acquire();
        removed = factory.runState().removeRunIfSame(threadId, run);
      } catch (RuntimeException unavailable) {
        return FactoryRunResourceResult.STALE;
      } finally {
        if (patterns != null) {
          patterns.resolve(removed);
        }
      }
      if (removed) {
        patterns.publish();
        factory.setChanged();
        RecipeLifecyclePublisher.resourceDiscarded(factory, threadId, List.of(run), resource);
      }
      return waiting;
    }
    var adjusted = RecipeLifecyclePublisher.applyFailureDecision(run, tick, decision);
    if (adjusted != run && factory.runState().replaceRunIfSame(threadId, run, adjusted)) {
      factory.setChanged();
      RecipeLifecyclePublisher.resourceFailed(factory, threadId, List.of(run), List.of(adjusted), resource);
    }
    return waiting;
  }

  private static boolean stillUnavailable(FactoryControllerBlockEntity factory, FrozenRecipeRun run, RecipeLifecycleEvents.ResourceFailure resource) {
    if (resource.type() != ResourceType.ENERGY) {
      return RecipeResourceExecutionRegistry.stillUnavailable(factory, List.of(run), resource.type());
    }
    if (resource == RecipeLifecycleEvents.ResourceFailure.ENERGY) {
      var ticket = RecipeExecutionTicket.captureEnergyInputs(factory).orElse(null);
      return ticket != null && planEnergyDebits(ticket, run.energyPerTick(), run.selectors().energyInput()) == null;
    }
    return false;
  }

  private record EnergyDebit(RecipePortSnapshot port, long amount) {
  }

  private static List<EnergyDebit> planEnergyDebits(RecipeExecutionTicket ticket, long amount, String selector) {
    long remaining = amount;
    var result = new ArrayList<EnergyDebit>();
    for (var port : ticket.ports()) {
      if (port.kind() != PortKind.ENERGY_INPUT || !port.selector().equals(selector)) {
        continue;
      }
      long available = Math.min(port.energy(), port.energyExtractable());
      long take = Math.min(remaining, available);
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

}
