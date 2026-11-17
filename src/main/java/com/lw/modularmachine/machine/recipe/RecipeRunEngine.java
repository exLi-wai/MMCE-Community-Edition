package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.recipe.RecipeRunStatus;
import com.lw.modularmachine.api.component.ResourceType;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceTransaction;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecution.TickStatus;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecutionRegistry;
import com.lw.modularmachine.machine.recipe.search.FluidOutputLayout;
import com.lw.modularmachine.machine.recipe.search.ItemOutputLayout;
import com.lw.modularmachine.machine.recipe.search.RecipeCandidate;
import com.lw.modularmachine.machine.recipe.search.RecipePlanner;
import com.lw.modularmachine.machine.recipe.search.RecipeResourceRequirements;
import com.lw.modularmachine.machine.upgrade.UpgradeLifecyclePublisher;
import com.lw.modularmachine.api.recipe.RecipeLifecycleEvents;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.machine.controller.ControllerRunState;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity.StartCostLease;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.api.upgrade.UpgradeRuntimeData.Phase;
import com.lw.modularmachine.machine.upgrade.UpgradeStartCosts;
import com.lw.modularmachine.machine.upgrade.UpgradeTickCosts.Status;
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

/**
 * Server-owned multi-resource paid-run lifecycle; output and clearing the run
 * share one commit decision.
 */
public final class RecipeRunEngine {

  private long lastAttempt = Long.MIN_VALUE;
  private RecipeRunStatus status = RecipeRunStatus.IDLE;
  private Work work;
  private UUID bindingRun;
  private StructureService.Registration binding;
  private boolean compatible;
  private String warned;

  private static final class Work {
    final FrozenRecipeRun run;
    final RecipeExecutionTicket ticket;
    final ItemOutputLayout layout;
    final FluidOutputLayout.Planning fluidPlan;
    final RecipeResourceExecutionRegistry.OutputPlanning resourcePlan;
    final RecipePlanner.Session<ItemResourceKey> planner;

    Work(FrozenRecipeRun run, RecipeExecutionTicket ticket, ServerLevel level) {
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
          .planRequirements(run.fluidOutputRequirements(level.registryAccess()), run.selectors().fluidOutput());
      resourcePlan = RecipeResourceExecutionRegistry.prepareOutputs(ticket, List.of(run));
      planner = claims.isEmpty() ? null : RecipePlanner.prepareOutputs(layout.snapshot(), candidate);
    }
  }

  private record EnergyDebit(RecipePortSnapshot port, long amount) {
  }

  public RecipeRunStatus status() {
    return status;
  }

  public void started() {
    work = null;
    binding = null;
    bindingRun = null;
    status = RecipeRunStatus.ADVANCED;
  }

  public void acknowledgeFinish() {
    if (status != RecipeRunStatus.FINISHED) {
      throw new IllegalStateException("No completed recipe to acknowledge");
    }
    work = null;
    binding = null;
    bindingRun = null;
    status = RecipeRunStatus.IDLE;
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

  public RecipeRunStatus tick(MachineControllerBlockEntity controller, ServerLevel level) {
    if (!level.getServer().isSameThread()) {
      throw new IllegalStateException("Recipe run tick outside server thread");
    }
    if (RecipeLifecyclePublisher.isDispatching()) {
      return RecipeRunStatus.PAUSED;
    }
    long now = level.getGameTime();
    if (lastAttempt == now) {
      return status = RecipeRunStatus.WAITING_TICK;
    }
    lastAttempt = now;
    var run = controller.runningRecipe().orElse(null);
    if (run == null) {
      invalidate();
      return status = RecipeRunStatus.IDLE;
    }
    if (!qualified(controller, run) || !PatternRunSources.available(controller, run)) {
      work = null;
      return status = controller.canCommitProduction() ? RecipeRunStatus.BINDING_CHANGED : RecipeRunStatus.PAUSED;
    }
    try {
      if (!run.pendingOutput()) {
        if (run.progress() < run.duration() && run.lastProgressTick() == now) {
          return status = RecipeRunStatus.WAITING_TICK;
        }
        var preparation = controller.upgradeRuntimeWork().prepareRecipeTick("main", List.of(run));
        if (preparation != RecipeTickPreparation.Status.ALLOWED) {
          return status = preparation == RecipeTickPreparation.Status.PENDING ? RecipeRunStatus.UPGRADE_DATA_PENDING : RecipeRunStatus.PAUSED;
        }
        if (!qualified(controller, run) || controller.runningRecipe().orElse(null) != run) {
          return status = RecipeRunStatus.PAUSED;
        }
        var tickCosts = controller.upgradeRuntimeWork().prepare("main", List.of(run));
        if (tickCosts.status() == Status.INSUFFICIENT) {
          return status = RecipeRunStatus.WAITING_UPGRADE_COST;
        }
        if (tickCosts.status() == Status.PENDING) {
          return status = RecipeRunStatus.UPGRADE_DATA_PENDING;
        }
        if (tickCosts.status() != Status.READY) {
          return status = RecipeRunStatus.UPGRADE_ERROR;
        }
        var energyOutputs = EnergyOutputTransactions.prepare(controller, List.of(run));
        if (energyOutputs.status() != EnergyOutputTransactions.Status.READY) {
          return status = switch (energyOutputs.status()) {
            case MISSING_PORT -> RecipeRunStatus.MISSING_ENERGY_OUTPUT;
            case BLOCKED -> RecipeRunStatus.OUTPUT_BLOCKED;
            default -> RecipeRunStatus.PAUSED;
          };
        }
        RecipeExecutionTicket energyTicket = null;
        var debits = List.<EnergyDebit>of();
        if (run.energyPerTick() > 0) {
          var captured = RecipeExecutionTicket.captureEnergyInputs(controller);
          if (captured.isEmpty()) {
            return status = RecipeRunStatus.PAUSED;
          }
          energyTicket = captured.orElseThrow();
          if (!RecipeResourceRequirements.has(energyTicket, PortKind.ENERGY_INPUT, run.selectors().energyInput())) {
            return status = RecipeRunStatus.MISSING_ENERGY_INPUT;
          }
          debits = planEnergyDebits(energyTicket, run.energyPerTick(), run.selectors().energyInput());
          if (debits == null) {
            return status = applyResourceFailure(controller, run, now, RecipeRunStatus.WAITING_ENERGY);
          }
        }
        var beforeTick = run;
        var resourceTick = RecipeResourceExecutionRegistry.prepareTick(controller, List.of(run));
        if (resourceTick.status() == TickStatus.STALE) {
          return status = RecipeRunStatus.PAUSED;
        }
        if (resourceTick.status() == TickStatus.MISSING_INPUT) {
          if (resourceTick.resource() == RecipeResourceDefinitions.FLUID) {
            return status = RecipeRunStatus.MISSING_FLUID_INPUT;
          }
          return status = resourceTick
              .resource() == RecipeResourceDefinitions.CHEMICAL ? RecipeRunStatus.MISSING_CHEMICAL_INPUT : RecipeRunStatus.MISSING_RESOURCE_INPUT;
        }
        if (resourceTick.status() == TickStatus.MISSING_OUTPUT) {
          if (resourceTick.resource() == RecipeResourceDefinitions.ITEM) {
            return status = RecipeRunStatus.OUTPUT_BLOCKED;
          }
          return status = resourceTick
              .resource() == RecipeResourceDefinitions.FLUID ? RecipeRunStatus.MISSING_FLUID_OUTPUT : RecipeRunStatus.MISSING_CHEMICAL_OUTPUT;
        }
        if (resourceTick.status() == TickStatus.OUTPUT_BLOCKED) {
          return status = RecipeRunStatus.OUTPUT_BLOCKED;
        }
        if (resourceTick.status() == TickStatus.INSUFFICIENT) {
          var waiting = resourceTick
              .resource() == RecipeResourceDefinitions.CHEMICAL ? RecipeRunStatus.WAITING_CHEMICAL : RecipeRunStatus.WAITING_RESOURCE;
          if (resourceTick.resource() == RecipeResourceDefinitions.FLUID) {
            waiting = RecipeRunStatus.WAITING_FLUID;
          }
          return status = applyResourceFailure(
              controller,
              run,
              now,
              waiting,
              RecipeLifecycleEvents.ResourceFailure.of(resourceTick.resource().resourceType()));
        }
        var additionalResources = resourceTick.prepared();
        var patterns = new PatternInputTransaction();
        if (additionalResources != null) {
          additionalResources.addPatterns(patterns);
        }
        var energyLeases = new ArrayList<MachinePortBlockEntity.EnergyStartLease>();
        var tickCostLeases = new ArrayList<StartCostLease>();
        var resourceTransaction = new RecipeResourceTransaction();
        resourceTransaction.phase().enlist(energyOutputs);
        var energyResources = resourceTransaction.phase();
        resourceTransaction.phase().track(tickCostLeases, StartCostLease::resolve);
        var ownerResources = resourceTransaction.phase();
        resourceTransaction.phase().enlist(additionalResources);
        resourceTransaction.phase().enlist(patterns);
        var owner = ownerResources
            .enlist(controller.runState().acquireUpdate(run), ControllerRunState.RunUpdateLease::resolve);
        boolean commit = false;
        try {
          var proof = controller.registration();
          if (!tickCosts.current()
              || !energyOutputs.current()
              || !controller.revalidateProduction(proof)
              || !qualified(controller, run)
              || energyTicket != null && !RecipeCommitGuard.validate(energyTicket)
              || !additionalResources.current()
              || !tickCosts.versionsCurrent()) {
            return status = RecipeRunStatus.PAUSED;
          }
          patterns.acquire();
          tickCosts.acquireInto(tickCostLeases);
          if (additionalResources != null) {
            additionalResources.acquire();
          }
          for (var debit : debits) {
            energyLeases.add(
                energyResources.enlist(
                    debit.port().identity().acquireEnergyStart(debit.port().revision()),
                    MachinePortBlockEntity.EnergyStartLease::resolve));
          }
          for (int index = 0; index < debits.size(); index++) {
            if (!energyLeases.get(index).extractExact(debits.get(index).amount())) {
              return status = RecipeRunStatus.STALE;
            }
          }
          energyOutputs.acquire();
          var advanced = run.advanced(now);
          if (advanced.progress() == advanced.duration()) {
            advanced = advanced.finished();
          }
          if (advanced == run) {
            return status = RecipeRunStatus.WAITING_TICK;
          }
          owner.install(advanced);
          commit = true;
          run = advanced;
        } finally {
          resourceTransaction.resolve(commit);
        }
        patterns.publish();
        energyOutputs.publish();
        if (additionalResources != null) {
          additionalResources.publish();
        }
        controller.upgradeRuntimeWork().clear(Phase.TICK, "main");
        UpgradeStartCosts.publish(controller, tickCostLeases);
        RecipeLifecyclePublisher.advanced(controller, beforeTick, run);
        RecipeResourceTransaction.publishAll(
            energyLeases,
            MachinePortBlockEntity.EnergyStartLease::publish,
            notification -> ModularMachine.LOGGER.warn("Committed recipe energy notification failed", notification));
        publishController(controller);
        if (controller.runningRecipe().orElse(null) != run) {
          return status = RecipeRunStatus.STALE;
        }
        if (!run.pendingOutput()) {
          return status = RecipeRunStatus.ADVANCED;
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
      return status = RecipeRunStatus.ERROR;
    }
  }

  private RecipeRunStatus applyResourceFailure(MachineControllerBlockEntity controller, FrozenRecipeRun run, long tick, RecipeRunStatus result) {
    var resource = switch (result) {
      case WAITING_ENERGY -> RecipeLifecycleEvents.ResourceFailure.ENERGY;
      case WAITING_CHEMICAL -> RecipeLifecycleEvents.ResourceFailure.CHEMICAL;
      default -> RecipeLifecycleEvents.ResourceFailure.FLUID;
    };
    return applyResourceFailure(controller, run, tick, result, resource);
  }

  private RecipeRunStatus applyResourceFailure(MachineControllerBlockEntity controller, FrozenRecipeRun run, long tick, RecipeRunStatus result, RecipeLifecycleEvents.ResourceFailure resource) {
    var decision = RecipeLifecyclePublisher.decideResourceFailure(controller, Optional.empty(), run, resource);
    if (controller.runningRecipe().orElse(null) != run
        || !controller.canCommitProduction()
        || !stillUnavailable(controller, run, resource)) {
      return RecipeRunStatus.PAUSED;
    }
    if (decision == RecipeLifecycleEvents.FailureDecision.DISCARD) {
      PatternInputTransaction patternRelease;
      try {
        patternRelease = PatternInputTransaction.release(controller, List.of(run));
      } catch (RuntimeException unavailable) {
        return RecipeRunStatus.PAUSED;
      }
      var resourceTransaction = new RecipeResourceTransaction();
      var owner = resourceTransaction.phase()
          .enlist(controller.runState().acquireUpdate(run), ControllerRunState.RunUpdateLease::resolve);
      resourceTransaction.phase().enlist(patternRelease);
      boolean commit = false;
      try {
        patternRelease.acquire();
        owner.installDiscard();
        commit = true;
      } finally {
        resourceTransaction.resolve(commit);
      }
      if (commit) {
        patternRelease.publish();
        RecipeLifecyclePublisher.resourceDiscarded(controller, run, resource);
        publishController(controller);
      }
      return result;
    }
    var adjusted = RecipeLifecyclePublisher.applyFailureDecision(run, tick, decision);
    if (adjusted == run) {
      return result;
    }
    var owner = controller.runState().acquireUpdate(run);
    boolean commit = false;
    try {
      owner.installFailure(adjusted);
      commit = true;
    } finally {
      owner.resolve(commit);
    }
    if (commit) {
      RecipeLifecyclePublisher.resourceFailed(controller, run, adjusted, resource);
      publishController(controller);
    }

    return result;
  }

  private static boolean stillUnavailable(MachineControllerBlockEntity controller, FrozenRecipeRun run, RecipeLifecycleEvents.ResourceFailure resource) {
    if (resource.type() != ResourceType.ENERGY) {
      return RecipeResourceExecutionRegistry.stillUnavailable(controller, List.of(run), resource.type());
    }
    if (resource == RecipeLifecycleEvents.ResourceFailure.ENERGY) {
      var ticket = RecipeExecutionTicket.captureEnergyInputs(controller).orElse(null);
      return ticket != null && planEnergyDebits(ticket, run.energyPerTick(), run.selectors().energyInput()) == null;
    }
    return false;
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

  private boolean qualified(MachineControllerBlockEntity controller, FrozenRecipeRun run) {
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

  private RecipeRunStatus flush(MachineControllerBlockEntity controller, FrozenRecipeRun run, ServerLevel level) {
    if (!qualified(controller, run)) {
      work = null;
      return RecipeRunStatus.PAUSED;
    }
    if (work == null || work.run != run || !work.ticket.dependenciesCurrent()) {
      work = null;
      var captured = RecipeExecutionTicket.captureOutputs(controller);
      if (captured.isEmpty()) {
        return RecipeRunStatus.PAUSED;
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
      return RecipeRunStatus.OUTPUT_PLANNING;
    }
    if (active.fluidPlan.status() == RecipePlanner.Status.NO_MATCH) {
      return RecipeRunStatus.OUTPUT_BLOCKED;
    }
    var fluidAdditions = active.fluidPlan.additions();
    if (active.resourcePlan.status() != RecipePlanner.Status.SELECTED) {
      var result = active.resourcePlan.advance(budget);
      budget -= result.workUsed();
    }
    if (active.resourcePlan.status() == RecipePlanner.Status.BUDGET_EXHAUSTED) {
      return RecipeRunStatus.OUTPUT_PLANNING;
    }
    if (active.resourcePlan.status() == RecipePlanner.Status.NO_MATCH) {
      return RecipeRunStatus.OUTPUT_BLOCKED;
    }
    var replacements = Map.<String, NonNullList<ItemStack>>of();
    if (active.planner != null) {
      var result = active.planner.advance(budget);
      if (result.status() == RecipePlanner.Status.BUDGET_EXHAUSTED) {
        return RecipeRunStatus.OUTPUT_PLANNING;
      }
      if (result.status() == RecipePlanner.Status.NO_MATCH) {
        return RecipeRunStatus.OUTPUT_BLOCKED;
      }
      var plan = result.plan().orElseThrow();
      // A selected plan may discard every optional output when no space remains.
      replacements = active.layout.prepare(plan);
    }
    var runtime = controller.upgradeRuntimeWork().prepare(Phase.FINISHED, "main", List.of(run));
    switch (runtime.status()) {
      case PENDING -> {
        return RecipeRunStatus.UPGRADE_DATA_PENDING;
      }
      case INSUFFICIENT -> {
        return RecipeRunStatus.WAITING_UPGRADE_COST;
      }
      case ERROR, INVALID -> {
        return RecipeRunStatus.UPGRADE_ERROR;
      }
      case READY -> {
      }
    }
    if (!runtime.current()) {
      return RecipeRunStatus.STALE;
    }
    var checked = active.ticket.refreshIfUnchanged().filter(RecipeCommitGuard::validate);
    if (checked.isEmpty() || !qualified(controller, run) || !runtime.versionsCurrent()) {
      work = null;
      return RecipeRunStatus.STALE;
    }
    var leases = new HashMap<String, MachinePortBlockEntity.ItemStartLease>();
    var fluidLeases = new HashMap<String, MachinePortBlockEntity.FluidStartLease>();
    var upgradeLeases = new ArrayList<StartCostLease>();
    var additionalResources = active.resourcePlan.prepared(checked.orElseThrow());
    if (!additionalResources.current()) {
      work = null;
      return RecipeRunStatus.STALE;
    }
    PatternInputTransaction patternRelease;
    try {
      patternRelease = PatternInputTransaction.release(controller, List.of(run));
    } catch (RuntimeException unavailable) {
      return RecipeRunStatus.PAUSED;
    }
    var resourceTransaction = new RecipeResourceTransaction();
    var itemResources = resourceTransaction.phase();
    var fluidResources = resourceTransaction.phase();
    resourceTransaction.phase().track(upgradeLeases, StartCostLease::resolve);
    var ownerResources = resourceTransaction.phase();
    resourceTransaction.phase().enlist(additionalResources);
    resourceTransaction.phase().enlist(patternRelease);
    var owner = ownerResources
        .enlist(controller.runState().acquireUpdate(run), ControllerRunState.RunUpdateLease::resolve);
    boolean commit = false;
    try {
      patternRelease.acquire();
      runtime.acquireInto(upgradeLeases);
      additionalResources.acquire();
      for (var port : checked.orElseThrow().ports()) {
        if (port.kind() == PortKind.ITEM_OUTPUT) {
          leases.put(
              port.partId(),
              itemResources.enlist(
                  port.identity().acquireItemStart(port.revision()),
                  MachinePortBlockEntity.ItemStartLease::resolve));
        } else if (fluidAdditions.containsKey(port.partId())) {
          fluidLeases.put(
              port.partId(),
              fluidResources.enlist(
                  port.identity().acquireFluidOutputStart(port.revision(), port.tankIndex()),
                  MachinePortBlockEntity.FluidStartLease::resolve));
        }
      }
      for (var port : checked.orElseThrow().ports()) {
        var replacement = replacements.get(port.partId());
        if (replacement != null) {
          leases.get(port.partId()).replacePrepared(replacement);
        }
        var addition = fluidAdditions.get(port.partId());
        if (addition != null && !fluidLeases.get(port.partId()).fillExact(addition)) {
          return RecipeRunStatus.STALE;
        }
      }
      owner.install(null);
      commit = true;
    } finally {
      resourceTransaction.resolve(commit);
      if (commit) {
        work = null;
      }
    }
    controller.upgradeRuntimeWork().clear(Phase.FINISHED, "main");
    patternRelease.publish();
    additionalResources.publish();
    UpgradeStartCosts.publish(controller, upgradeLeases);
    publishController(controller);
    UpgradeLifecyclePublisher.recipeFinished(controller, "main", List.of(run));
    RecipeResourceTransaction.publishAll(
        leases.values(),
        MachinePortBlockEntity.ItemStartLease::publish,
        notification -> ModularMachine.LOGGER.warn("Committed output notification failed", notification));
    RecipeResourceTransaction.publishAll(
        fluidLeases.values(),
        MachinePortBlockEntity.FluidStartLease::publish,
        notification -> ModularMachine.LOGGER.warn("Committed fluid output notification failed", notification));

    return RecipeRunStatus.FINISHED;
  }

  private static void publishController(MachineControllerBlockEntity controller) {
    try {
      controller.setChanged();
    } catch (RuntimeException notification) {
      ModularMachine.LOGGER.warn("Committed run notification failed", notification);
    }
  }
}
