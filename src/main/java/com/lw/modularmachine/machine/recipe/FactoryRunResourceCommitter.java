package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.recipe.search.RecipeResourceRequirements;
import com.lw.modularmachine.machine.factory.UpgradeLifecyclePublisher;
import com.lw.modularmachine.api.recipe.RecipeLifecycleEvents;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity.StartCostLease;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeRuntimeData.Phase;
import com.lw.modularmachine.machine.factory.FactoryUpgradeStartCosts;
import com.lw.modularmachine.machine.factory.FactoryUpgradeTickCosts;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.fluids.FluidStack;

/** Atomic FE/t and fluid/t fee transaction for one factory run. */
public final class FactoryRunResourceCommitter {
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
    MISSING_ENERGY_OUTPUT,
    OUTPUT_BLOCKED,
    MISSING_FLUID_INPUT,
    MISSING_CHEMICAL_INPUT
  }

  private FactoryRunResourceCommitter() {
  }

  public static Result tick(FactoryControllerBlockEntity factory, String threadId, FrozenItemRun run) {
    if (!(factory.getLevel() instanceof ServerLevel level)
        || run.pendingOutput()
        || UpgradeLifecyclePublisher.isDispatching(factory)) {
      return Result.STALE;
    }
    if (!factory.canCommitStructure() || !run.matchesBindings(factory.registration().pattern.definition)) {
      return Result.STALE;
    }
    if (run.lastProgressTick() == level.getGameTime()) {
      return Result.STALE;
    }
    if (!PatternRunSources.available(factory, run)) {
      return Result.STALE;
    }
    var tickCosts = factory.upgradeRuntimeWork().prepare(threadId, List.of(run));
    if (tickCosts.status() == FactoryUpgradeTickCosts.Status.INSUFFICIENT) {
      return Result.WAITING_UPGRADE;
    }
    if (tickCosts.status() == FactoryUpgradeTickCosts.Status.PENDING) {
      return Result.UPGRADE_PENDING;
    }
    if (tickCosts.status() != FactoryUpgradeTickCosts.Status.READY) {
      return Result.UPGRADE_ERROR;
    }
    var energyOutputs = EnergyOutputTransactions.prepare(factory, List.of(run));
    if (energyOutputs.status() != EnergyOutputTransactions.Status.READY) {
      return switch (energyOutputs.status()) {
        case MISSING_PORT -> Result.MISSING_ENERGY_OUTPUT;
        case BLOCKED -> Result.OUTPUT_BLOCKED;
        default -> Result.STALE;
      };
    }
    var beforeTick = run;
    RecipeExecutionTicket energyTicket = null;
    RecipeExecutionTicket fluidTicket = null;
    List<EnergyDebit> energyDebits = List.of();
    List<FluidDebit> fluidDebits = List.of();
    if (run.energyPerTick() > 0) {
      energyTicket = RecipeExecutionTicket.captureEnergyInputs(factory).orElse(null);
      if (energyTicket == null) {
        return Result.WAITING_ENERGY;
      }
      if (!RecipeResourceRequirements.has(energyTicket, PortKind.ENERGY_INPUT, run.selectors().energyInput())) {
        return Result.MISSING_ENERGY_INPUT;
      }
      energyDebits = planEnergyDebits(energyTicket, run.energyPerTick(), run.selectors().energyInput());
      if (energyDebits == null) {
        return applyResourceFailure(factory, threadId, run, level.getGameTime(), Result.WAITING_ENERGY);
      }
    }
    var fluidFee = run.fluidPerTick(level.registryAccess());
    if (!fluidFee.isEmpty()) {
      fluidTicket = RecipeExecutionTicket.captureFluidInputs(factory).orElse(null);
      if (fluidTicket == null) {
        return Result.WAITING_FLUID;
      }
      if (!run.matchesInputGroups(fluidTicket)) {
        return Result.STALE;
      }
      fluidTicket = fluidTicket.forRun(run).orElse(null);
      if (fluidTicket == null) {
        return Result.STALE;
      }
      if (!RecipeResourceRequirements.has(fluidTicket, PortKind.FLUID_INPUT, run.selectors().fluidPerTick())) {
        return Result.MISSING_FLUID_INPUT;
      }
      fluidDebits = planFluidDebits(fluidTicket, fluidFee, run.selectors().fluidPerTick());
      if (fluidDebits == null) {
        return applyResourceFailure(factory, threadId, run, level.getGameTime(), Result.WAITING_FLUID);
      }
    }
    RecipeExecutionTicket chemicalTicket = null;
    ChemicalTransactions.Batch chemicals = null;
    if (!run.chemicalPerTick().isEmpty()) {
      var captured = RecipeExecutionTicket.captureChemicalInputs(factory).orElse(null);
      if (captured == null || !run.matchesInputGroups(captured)) {
        return Result.STALE;
      }
      chemicalTicket = captured.forRun(run).orElse(null);
      if (chemicalTicket == null) {
        return Result.STALE;
      }
      if (ChemicalTransactions.missingInputs(chemicalTicket, run.chemicalPerTick())) {
        return Result.MISSING_CHEMICAL_INPUT;
      }
      chemicals = ChemicalTransactions.inputs(chemicalTicket, run.chemicalPerTick());
      if (chemicals == null) {
        return applyResourceFailure(factory, threadId, run, level.getGameTime(), Result.WAITING_CHEMICAL);
      }
    }
    if (energyTicket == null
        && fluidTicket == null
        && chemicalTicket == null
        && !tickCosts.hasRuntimeWork()
        && run.energyOutputPerTick() == 0) {
      return Result.STALE;
    }
    var energyLeases = new ArrayList<MachinePortBlockEntity.EnergyStartLease>();
    var patterns = new PatternInputTransaction();
    for (var debit : fluidDebits) {
      if (debit.port().patternInput()) {
        patterns.consumeFluid(debit.port(), debit.amount());
      }
    }
    if (chemicals != null) {
      chemicals.addPatterns(patterns);
    }
    var fluidLeases = new ArrayList<MachinePortBlockEntity.FluidStartLease>();
    var tickCostLeases = new ArrayList<StartCostLease>();
    boolean committed = false;
    try {
      if (!tickCosts.current()
          || !energyOutputs.current()
          || energyTicket == null && fluidTicket == null && !factory.revalidateStructure(factory.registration())
          || energyTicket != null && !RecipeCommitGuard.validate(energyTicket)
          || fluidTicket != null && !RecipeCommitGuard.validate(fluidTicket)
          || chemicalTicket != null && !RecipeCommitGuard.validate(chemicalTicket)
          || !tickCosts.versionsCurrent()) {
        return Result.STALE;
      }
      patterns.acquire();
      tickCosts.acquireInto(tickCostLeases);
      if (chemicals != null) {
        chemicals.acquire();
      }
      for (var debit : energyDebits) {
        energyLeases.add(debit.port().identity().acquireEnergyStart(debit.port().revision()));
      }
      for (int index = 0; index < energyDebits.size(); index++) {
        var debit = energyDebits.get(index);
        if (!energyLeases.get(index).extractExact(debit.amount())) {
          return Result.STALE;
        }
      }
      energyOutputs.acquire();
      for (var debit : fluidDebits) {
        if (debit.port().patternInput()) {
          continue;
        }
        var lease = debit.port().identity().acquireFluidStart(debit.port().revision(), debit.port().tankIndex());
        fluidLeases.add(lease);
        if (!lease.drainExact(debit.amount())) {
          return Result.STALE;
        }
      }
      var advanced = run.advanced(level.getGameTime());
      if (advanced == run) {
        return Result.STALE;
      }
      if (advanced.progress() == advanced.duration()) {
        advanced = advanced.finished();
      }
      if (!factory.replaceRunIfSame(threadId, run, advanced)) {
        return Result.STALE;
      }
      committed = true;
      run = advanced;
      return Result.ADVANCED;
    } catch (RuntimeException failure) {
      ModularMachine.LOGGER.warn("Factory run resource commit failed at {}", factory.getBlockPos(), failure);
      return Result.STALE;
    } finally {
      energyOutputs.resolve(committed);
      for (var lease : energyLeases) {
        lease.resolve(committed);
      }
      for (var lease : fluidLeases) {
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
        energyOutputs.publish();
        patterns.publish();
        if (chemicals != null) {
          chemicals.publish();
        }
        factory.upgradeRuntimeWork().clear(Phase.TICK, threadId);
        FactoryUpgradeStartCosts.publish(factory, tickCostLeases);
        if (RecipeLifecyclePublisher.hasListeners(RecipeLifecycleEvents.Type.TICK)) {
          RecipeLifecyclePublisher.advanced(factory, threadId, List.of(beforeTick), List.of(run));
        }
        energyLeases.forEach(lease -> {
          try {
            lease.publish();
          } catch (RuntimeException failure) {
            ModularMachine.LOGGER.warn("Factory energy notification failed", failure);
          }
        });
        fluidLeases.forEach(lease -> {
          try {
            lease.publish();
          } catch (RuntimeException failure) {
            ModularMachine.LOGGER.warn("Factory fluid notification failed", failure);
          }
        });
        factory.setChanged();
      }
    }
  }

  private static Result applyResourceFailure(FactoryControllerBlockEntity factory, String threadId, FrozenItemRun run, long tick, Result waiting) {
    var resource = switch (waiting) {
      case WAITING_ENERGY -> RecipeLifecycleEvents.ResourceFailure.ENERGY;
      case WAITING_CHEMICAL -> RecipeLifecycleEvents.ResourceFailure.CHEMICAL;
      default -> RecipeLifecycleEvents.ResourceFailure.FLUID;
    };
    var decision = RecipeLifecyclePublisher.decideResourceFailure(factory, Optional.of(threadId), run, resource);
    if (!factory.canCommitStructure()
        || factory.runSet(threadId).find(run.runId()).orElse(null) != run
        || !stillUnavailable(factory, run, resource)) {
      return Result.STALE;
    }
    if (decision == RecipeLifecycleEvents.FailureDecision.DISCARD) {
      PatternInputTransaction patterns = null;
      boolean removed = false;
      try {
        patterns = PatternInputTransaction.release(factory, List.of(run));
        patterns.acquire();
        removed = factory.removeRunIfSame(threadId, run);
      } catch (RuntimeException unavailable) {
        return Result.STALE;
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
    if (adjusted != run && factory.replaceRunIfSame(threadId, run, adjusted)) {
      factory.setChanged();
      RecipeLifecyclePublisher.resourceFailed(factory, threadId, List.of(run), List.of(adjusted), resource);
    }
    return waiting;
  }

  private static boolean stillUnavailable(FactoryControllerBlockEntity factory, FrozenItemRun run, RecipeLifecycleEvents.ResourceFailure resource) {
    if (resource == RecipeLifecycleEvents.ResourceFailure.CHEMICAL) {
      return ChemicalTransactions.stillUnavailable(factory, List.of(run));
    }
    if (resource == RecipeLifecycleEvents.ResourceFailure.ENERGY) {
      var ticket = RecipeExecutionTicket.captureEnergyInputs(factory).orElse(null);
      return ticket != null && planEnergyDebits(ticket, run.energyPerTick(), run.selectors().energyInput()) == null;
    }
    var ticket = RecipeExecutionTicket.captureFluidInputs(factory).orElse(null);
    if (ticket == null || !run.matchesInputGroups(ticket)) {
      return false;
    }

    var source = ticket.forRun(run).orElse(null);
    return source != null
        && planFluidDebits(
            source,
            run.fluidPerTick(((ServerLevel) factory.getLevel()).registryAccess()),
            run.selectors().fluidPerTick()) == null;
  }

  private record EnergyDebit(RecipeExecutionTicket.PortSnapshot port, long amount) {
  }

  private record FluidDebit(RecipeExecutionTicket.PortSnapshot port, FluidStack amount) {
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

  private static List<FluidDebit> planFluidDebits(RecipeExecutionTicket ticket, FluidStack requirement, String selector) {
    int remaining = requirement.getAmount();
    var result = new ArrayList<FluidDebit>();
    for (var port : ticket.ports()) {
      if (port.kind() != PortKind.FLUID_INPUT
          || !port.selector().equals(selector)
          || !FluidStack.isSameFluidSameComponents(port.fluid(), requirement)) {
        continue;
      }
      int take = (int) Math.min(remaining, port.fluidAmount());
      if (take > 0) {
        result.add(new FluidDebit(port, requirement.copyWithAmount(take)));
        remaining -= take;
      }
      if (remaining == 0) {
        return List.copyOf(result);
      }
    }
    return null;
  }
}
