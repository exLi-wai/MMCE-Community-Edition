package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity.StartCostLease;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.factory.FactoryUpgradeEvents;
import com.lw.modularmachine.machine.factory.FactoryUpgradeRuntimeData.Phase;
import com.lw.modularmachine.machine.factory.FactoryUpgradeStartCosts;
import com.lw.modularmachine.machine.factory.FactoryUpgradeTickCosts.Status;
import com.lw.modularmachine.machine.structure.StructureService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Server-owned item-only paid-run lifecycle; output and clearing the run share
 * one commit decision.
 */
public final class ItemRunEngine {
  public enum Result {
    IDLE,
    WAITING_TICK,
    PAUSED,
    BINDING_CHANGED,
    ADVANCED,
    WAITING_ENERGY,
    WAITING_FLUID,
    OUTPUT_PLANNING,
    OUTPUT_BLOCKED,
    STALE,
    FINISHED,
    ERROR,
    UPGRADE_CONFLICT,
    UPGRADE_ERROR,
    WAITING_UPGRADE_COST,
    UPGRADE_DATA_PENDING
  }

  private long lastAttempt = Long.MIN_VALUE;
  private Result status = Result.IDLE;
  private Work work;
  private UUID bindingRun;
  private StructureService.Registration binding;
  private boolean compatible;
  private String warned;

  private static final class Work {
    final FrozenItemRun run;
    final RecipeExecutionTicket ticket;
    final ItemOutputLayout layout;
    final FluidOutputLayout.Planning fluidPlan;
    final RecipePlanner.Session<ItemResourceKey> planner;

    Work(FrozenItemRun run, RecipeExecutionTicket ticket, ServerLevel level) {
      this.run = run;
      this.ticket = ticket;
      var claims = run.outputClaims(level.registryAccess());
      var candidate = claims.isEmpty() ? null : RecipeCandidate.outputOnly(run.recipeId() + "/" + run.runId(), claims);
      var keys = new HashSet<ItemResourceKey>();
      if (candidate != null) {
        for (var output : candidate.outputs()) {
          keys.add(output.key());
        }
      }
      layout = new ItemOutputLayout(ticket, keys);
      fluidPlan = new FluidOutputLayout(ticket, level.registryAccess())
          .plan(run.fluidOutputClaims(level.registryAccess()), run.selectors().fluidOutput());
      planner = claims.isEmpty() ? null : RecipePlanner.prepareOutputs(layout.snapshot, candidate);
    }
  }

  private record EnergyDebit(RecipeExecutionTicket.PortSnapshot port, long amount) {
  }

  private record FluidDebit(RecipeExecutionTicket.PortSnapshot port, FluidStack amount) {
  }

  public Result status() {
    return status;
  }

  /**
   * Keep lastAttempt: same-instance loads and topology changes cannot manufacture
   * a second tick.
   */
  public void invalidate() {
    work = null;
    binding = null;
    bindingRun = null;
  }

  public Result tick(MachineControllerBlockEntity controller, ServerLevel level) {
    if (!level.getServer().isSameThread()) {
      throw new IllegalStateException("Recipe run tick outside server thread");
    }
    if (RecipeLifecycleEvents.isDispatching()) {
      return Result.PAUSED;
    }
    long now = level.getGameTime();
    if (lastAttempt == now) {
      return status = Result.WAITING_TICK;
    }
    lastAttempt = now;
    var run = controller.runningRecipe().orElse(null);
    if (run == null) {
      invalidate();
      return status = Result.IDLE;
    }
    if (!qualified(controller, run)) {
      work = null;
      return status = controller.canCommitProduction() ? Result.BINDING_CHANGED : Result.PAUSED;
    }
    try {
      if (!run.pendingOutput()) {
        if (run.progress() < run.duration() && run.lastProgressTick() == now) {
          return status = Result.WAITING_TICK;
        }
        var tickCosts = controller.upgradeRuntimeWork().prepare("main", List.of(run));
        if (tickCosts.status() == Status.INSUFFICIENT) {
          return status = Result.WAITING_UPGRADE_COST;
        }
        if (tickCosts.status() == Status.PENDING) {
          return status = Result.UPGRADE_DATA_PENDING;
        }
        if (tickCosts.status() != Status.READY) {
          return status = Result.UPGRADE_ERROR;
        }
        RecipeExecutionTicket energyTicket = null;
        RecipeExecutionTicket fluidTicket = null;
        var debits = List.<EnergyDebit>of();
        var fluidDebits = List.<FluidDebit>of();
        if (run.energyPerTick() > 0) {
          var captured = RecipeExecutionTicket.captureEnergyInputs(controller);
          if (captured.isEmpty()) {
            return status = Result.PAUSED;
          }
          energyTicket = captured.orElseThrow();
          debits = planEnergyDebits(energyTicket, run.energyPerTick(), run.selectors().energyInput());
          if (debits == null) {
            return status = applyResourceFailure(controller, run, now, Result.WAITING_ENERGY);
          }
        }
        var fluidFee = run.fluidPerTick(level.registryAccess());
        if (!fluidFee.isEmpty()) {
          var captured = RecipeExecutionTicket.captureFluidInputs(controller);
          if (captured.isEmpty()) {
            return status = Result.PAUSED;
          }
          if (!run.matchesInputGroups(captured.orElseThrow())) {
            return status = Result.PAUSED;
          }
          fluidTicket = captured.orElseThrow().withInputGroup(run.inputGroup());
          fluidDebits = planFluidDebits(fluidTicket, fluidFee, run.selectors().fluidPerTick());
          if (fluidDebits == null) {
            return status = applyResourceFailure(controller, run, now, Result.WAITING_FLUID);
          }
        }
        var beforeTick = run;
        var owner = controller.acquireRunUpdate(run);
        var energyLeases = new ArrayList<MachinePortBlockEntity.EnergyStartLease>();
        var fluidLeases = new ArrayList<MachinePortBlockEntity.FluidStartLease>();
        var tickCostLeases = new ArrayList<StartCostLease>();
        boolean commit = false;
        try {
          var proof = controller.registration();
          if (!tickCosts.current()
              || !controller.revalidateProduction(proof)
              || !qualified(controller, run)
              || energyTicket != null && !RecipeCommitGuard.validate(energyTicket)
              || fluidTicket != null && !RecipeCommitGuard.validate(fluidTicket)
              || !tickCosts.versionsCurrent()) {
            return status = Result.PAUSED;
          }
          tickCosts.acquireInto(tickCostLeases);
          for (var debit : debits) {
            energyLeases.add(debit.port().identity().acquireEnergyStart(debit.port().revision()));
          }
          for (int index = 0; index < debits.size(); index++) {
            if (!energyLeases.get(index).extractExact(debits.get(index).amount())) {
              return status = Result.STALE;
            }
          }
          for (var debit : fluidDebits) {
            fluidLeases.add(debit.port().identity().acquireFluidStart(debit.port().revision()));
          }
          for (int index = 0; index < fluidDebits.size(); index++) {
            if (!fluidLeases.get(index).drainExact(fluidDebits.get(index).amount())) {
              return status = Result.STALE;
            }
          }
          var advanced = run.advanced(now);
          if (advanced.progress() == advanced.duration()) {
            advanced = advanced.finished();
          }
          if (advanced == run) {
            return status = Result.WAITING_TICK;
          }
          owner.install(advanced);
          commit = true;
          run = advanced;
        } finally {
          for (var lease : fluidLeases) {
            lease.resolve(commit);
          }
          for (var lease : energyLeases) {
            lease.resolve(commit);
          }
          for (var lease : tickCostLeases) {
            lease.resolve(commit);
          }
          owner.resolve(commit);
        }
        controller.upgradeRuntimeWork().clear(Phase.TICK, "main");
        FactoryUpgradeStartCosts.publish(controller, tickCostLeases);
        RecipeLifecycleEvents.advanced(controller, beforeTick, run);
        for (var lease : energyLeases) {
          try {
            lease.publish();
          } catch (RuntimeException notification) {
            ModularMachine.LOGGER.warn("Committed recipe energy notification failed", notification);
          }
        }
        for (var lease : fluidLeases) {
          try {
            lease.publish();
          } catch (RuntimeException notification) {
            ModularMachine.LOGGER.warn("Committed recipe fluid notification failed", notification);
          }
        }
        publishController(controller);
        if (controller.runningRecipe().orElse(null) != run) {
          return status = Result.STALE;
        }
        if (!run.pendingOutput()) {
          return status = Result.ADVANCED;
        }
      }
      return status = flush(controller, run, level);
    } catch (RuntimeException failure) {
      work = null;
      String key = run.runId() + ":" + failure.getClass().getName() + ":" + failure.getMessage();
      if (!key.equals(warned)) {
        warned = key;
        ModularMachine.LOGGER.warn("Paid item run paused at {}: {}", controller.getBlockPos(), failure.getMessage());
      }
      return status = Result.ERROR;
    }
  }

  private Result applyResourceFailure(MachineControllerBlockEntity controller, FrozenItemRun run, long tick, Result result) {
    var resource = result == Result.WAITING_ENERGY ? RecipeLifecycleEvents.ResourceFailure.ENERGY : RecipeLifecycleEvents.ResourceFailure.FLUID;
    var decision = RecipeLifecycleEvents.decideResourceFailure(controller, Optional.empty(), run, resource);
    if (controller.runningRecipe().orElse(null) != run
        || !controller.canCommitProduction()
        || !stillUnavailable(controller, run, resource)) {
      return Result.PAUSED;
    }
    if (decision == RecipeLifecycleEvents.FailureDecision.DISCARD) {
      var owner = controller.acquireRunUpdate(run);
      boolean commit = false;
      try {
        owner.installDiscard();
        commit = true;
      } finally {
        owner.resolve(commit);
      }
      if (commit) {
        RecipeLifecycleEvents.resourceDiscarded(controller, run, resource);
        publishController(controller);
      }
      return result;
    }
    var adjusted = RecipeLifecycleEvents.applyFailureDecision(run, tick, decision);
    if (adjusted == run) {
      return result;
    }
    var owner = controller.acquireRunUpdate(run);
    boolean commit = false;
    try {
      owner.installFailure(adjusted);
      commit = true;
    } finally {
      owner.resolve(commit);
    }
    if (commit) {
      RecipeLifecycleEvents.resourceFailed(controller, run, adjusted, resource);
      publishController(controller);
    }

    return result;
  }

  private static boolean stillUnavailable(MachineControllerBlockEntity controller, FrozenItemRun run, RecipeLifecycleEvents.ResourceFailure resource) {
    if (resource == RecipeLifecycleEvents.ResourceFailure.ENERGY) {
      var ticket = RecipeExecutionTicket.captureEnergyInputs(controller).orElse(null);
      return ticket != null && planEnergyDebits(ticket, run.energyPerTick(), run.selectors().energyInput()) == null;
    }
    var ticket = RecipeExecutionTicket.captureFluidInputs(controller).orElse(null);
    if (ticket == null || !run.matchesInputGroups(ticket)) {
      return false;
    }

    return planFluidDebits(
        ticket.withInputGroup(run.inputGroup()),
        run.fluidPerTick(controller.getLevel().registryAccess()),
        run.selectors().fluidPerTick()) == null;
  }

  private static List<EnergyDebit> planEnergyDebits(RecipeExecutionTicket ticket, long amount, String selector) {
    long remaining = amount;
    var debits = new ArrayList<EnergyDebit>();
    for (var port : ticket.ports()) {
      if (port.kind() != PortKind.ENERGY_INPUT || !port.selector().equals(selector)) {
        continue;
      }
      long debit = Math.min(remaining, port.energyExtractable());
      if (debit > 0) {
        debits.add(new EnergyDebit(port, debit));
        remaining -= debit;
      }
      if (remaining == 0) {
        return List.copyOf(debits);
      }
    }

    return null;
  }

  private static List<FluidDebit> planFluidDebits(RecipeExecutionTicket ticket, FluidStack required, String selector) {
    int remaining = required.getAmount();
    var debits = new ArrayList<FluidDebit>();
    for (var port : ticket.ports()) {
      if (port.kind() != PortKind.FLUID_INPUT || !port.selector().equals(selector)) {
        continue;
      }
      var stored = port.fluid();
      if (!FluidStack.isSameFluidSameComponents(stored, required)) {
        continue;
      }
      int amount = Math.min(remaining, stored.getAmount());
      if (amount > 0) {
        debits.add(new FluidDebit(port, required.copyWithAmount(amount)));
        remaining -= amount;
      }
      if (remaining == 0) {
        return List.copyOf(debits);
      }
    }

    return null;
  }

  private boolean qualified(MachineControllerBlockEntity controller, FrozenItemRun run) {
    var current = controller.registration();
    if (current == null
        || !controller.canCommitProduction()
        || !controller.machineId().equals(run.machineId())
        || controller.runningRecipe().orElse(null) != run
        || controller.registration() != current) {
      return false;
    }
    if (binding != current || !run.runId().equals(bindingRun)) {
      binding = current;
      bindingRun = run.runId();
      compatible = run.matchesBindings(current.pattern.definition);
    }
    return compatible;
  }

  private Result flush(MachineControllerBlockEntity controller, FrozenItemRun run, ServerLevel level) {
    if (!qualified(controller, run)) {
      work = null;
      return Result.PAUSED;
    }
    if (work == null || work.run != run || !work.ticket.dependenciesCurrent()) {
      work = null;
      var captured = RecipeExecutionTicket.captureOutputs(controller);
      if (captured.isEmpty()) {
        return Result.PAUSED;
      }
      work = new Work(run, captured.orElseThrow(), level);
    }
    var active = work;
    int budget = MachineConfig.RECIPE_OUTPUT_WORK.get();
    if (active.fluidPlan.status() == RecipePlanner.Status.BUDGET_EXHAUSTED) {
      var fluidResult = active.fluidPlan.advance(budget);
      budget -= fluidResult.workUsed();
    }
    if (active.fluidPlan.status() == RecipePlanner.Status.BUDGET_EXHAUSTED) {
      return Result.OUTPUT_PLANNING;
    }
    if (active.fluidPlan.status() == RecipePlanner.Status.NO_MATCH) {
      return Result.OUTPUT_BLOCKED;
    }
    var fluidAdditions = active.fluidPlan.additions();
    var replacements = Map.<String, NonNullList<ItemStack>>of();
    if (active.planner != null) {
      var result = active.planner.advance(budget);
      if (result.status() == RecipePlanner.Status.BUDGET_EXHAUSTED) {
        return Result.OUTPUT_PLANNING;
      }
      if (result.status() == RecipePlanner.Status.NO_MATCH) {
        return Result.OUTPUT_BLOCKED;
      }
      var plan = result.plan().orElseThrow();
      if (plan.insertions().isEmpty()) {
        throw new IllegalStateException("Paid output plan contains no insertions");
      }
      replacements = active.layout.prepare(plan);
      if (replacements.isEmpty()) {
        throw new IllegalStateException("Paid output plan contains no inventory replacements");
      }
    }
    var runtime = controller.upgradeRuntimeWork().prepare(Phase.FINISHED, "main", List.of(run));
    switch (runtime.status()) {
      case PENDING -> {
        return Result.UPGRADE_DATA_PENDING;
      }
      case INSUFFICIENT -> {
        return Result.WAITING_UPGRADE_COST;
      }
      case ERROR, INVALID -> {
        return Result.UPGRADE_ERROR;
      }
      case READY -> {
      }
    }
    if (!runtime.current()) {
      return Result.STALE;
    }
    var checked = active.ticket.refreshIfUnchanged().filter(RecipeCommitGuard::validate);
    if (checked.isEmpty() || !qualified(controller, run) || !runtime.versionsCurrent()) {
      work = null;
      return Result.STALE;
    }
    var leases = new HashMap<String, MachinePortBlockEntity.ItemStartLease>();
    var fluidLeases = new HashMap<String, MachinePortBlockEntity.FluidStartLease>();
    var upgradeLeases = new ArrayList<StartCostLease>();
    var owner = controller.acquireRunUpdate(run);
    boolean commit = false;
    try {
      runtime.acquireInto(upgradeLeases);
      for (var port : checked.orElseThrow().ports()) {
        if (port.kind() == PortKind.ITEM_OUTPUT) {
          leases.put(port.partId(), port.identity().acquireItemStart(port.revision()));
        } else if (fluidAdditions.containsKey(port.partId())) {
          fluidLeases.put(port.partId(), port.identity().acquireFluidOutputStart(port.revision()));
        }
      }
      for (var port : checked.orElseThrow().ports()) {
        var replacement = replacements.get(port.partId());
        if (replacement != null) {
          leases.get(port.partId()).replacePrepared(replacement);
        }
        var addition = fluidAdditions.get(port.partId());
        if (addition != null && !fluidLeases.get(port.partId()).fillExact(addition)) {
          return Result.STALE;
        }
      }
      owner.install(null);
      commit = true;
    } finally {
      for (var lease : leases.values()) {
        lease.resolve(commit);
      }
      for (var lease : fluidLeases.values()) {
        lease.resolve(commit);
      }
      for (var lease : upgradeLeases) {
        lease.resolve(commit);
      }
      owner.resolve(commit);
      if (commit) {
        work = null;
      }
    }
    controller.upgradeRuntimeWork().clear(Phase.FINISHED, "main");
    FactoryUpgradeStartCosts.publish(controller, upgradeLeases);
    publishController(controller);
    FactoryUpgradeEvents.recipeFinished(controller, "main", List.of(run));
    for (var lease : leases.values()) {
      try {
        lease.publish();
      } catch (RuntimeException notification) {
        ModularMachine.LOGGER.warn("Committed output notification failed", notification);
      }
    }
    for (var lease : fluidLeases.values()) {
      try {
        lease.publish();
      } catch (RuntimeException notification) {
        ModularMachine.LOGGER.warn("Committed fluid output notification failed", notification);
      }
    }

    return Result.FINISHED;
  }

  private static void publishController(MachineControllerBlockEntity controller) {
    try {
      controller.setChanged();
    } catch (RuntimeException notification) {
      ModularMachine.LOGGER.warn("Committed run notification failed", notification);
    }
  }
}
