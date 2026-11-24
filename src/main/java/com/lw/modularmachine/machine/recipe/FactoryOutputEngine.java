package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.recipe.resource.RecipeResourceTransaction;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecutionRegistry;
import com.lw.modularmachine.machine.recipe.search.FluidOutputLayout;
import com.lw.modularmachine.machine.recipe.search.FluidResourceKey;
import com.lw.modularmachine.machine.recipe.search.ItemOutputLayout;
import com.lw.modularmachine.machine.recipe.search.RecipePlanner;
import com.lw.modularmachine.machine.recipe.search.RecipePlan;
import com.lw.modularmachine.machine.recipe.search.RecipeResourceRequirements.RequiredPort;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.upgrade.UpgradeLifecyclePublisher;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.factory.FactoryRunSet;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.api.upgrade.UpgradeRuntimeData;
import com.lw.modularmachine.machine.upgrade.UpgradeStartCosts;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Resumable, atomic output planning for every frozen run in a factory thread.
 */
public final class FactoryOutputEngine {
  public enum Result {
    PLANNING,
    BLOCKED,
    STALE,
    COMMITTED,
    UPGRADE_PENDING,
    WAITING_UPGRADE,
    UPGRADE_ERROR
  }

  private static final class Work {
    final FactoryRunSet set;
    final RecipeOutputClaims<ItemResourceKey> itemTotals = new RecipeOutputClaims<>();
    final RecipeOutputClaims<FluidResourceKey> fluidTotals = new RecipeOutputClaims<>();
    final RecipeResourceExecutionRegistry.OutputClaims extensions = new RecipeResourceExecutionRegistry.OutputClaims();
    final Set<RequiredPort> outputPorts = new HashSet<>();
    int runIndex, itemRow, fluidRow;
    StructureService.Registration registration;
    long structureRevision;
    int bindingIndex;
    boolean mismatched;
    boolean claimsReady;
    RecipePlanner.PaidOutputs<ItemResourceKey> itemClaims;
    RecipePlanner.PaidOutputs<FluidResourceKey> fluidClaims;
    RecipeExecutionTicket ticket;
    RecipeExecutionTicket extensionTicket;
    RecipeExecutionTicket placementProof;
    final Set<String> changedParts = new HashSet<>();
    ItemOutputLayout.Capture items;
    FluidOutputLayout.Capture fluids;
    ItemOutputLayout itemLayout;
    RecipePlanner.Session<ItemResourceKey> itemPlanner;
    FluidOutputLayout.Planning fluidPlanner;
    RecipeResourceExecutionRegistry.OutputPlanning resourcePlanner;

    Work(FactoryRunSet set) {
      this.set = set;
    }

    int captureClaims(ServerLevel level, StructureService.Registration current, int budget) {
      long revision = current.verifier.revision();
      if (registration != current || structureRevision != revision) {
        registration = current;
        structureRevision = revision;
        bindingIndex = 0;
        mismatched = false;
        ticket = null;
        changedParts.clear();
      }
      int used = 0;
      while (!mismatched && used < budget && (runIndex < set.runs().size() || bindingIndex < set.runs().size())) {
        used++;
        // A changed structure invalidates the binding proof, not the frozen claims.
        if (bindingIndex <= runIndex && bindingIndex < set.runs().size()) {
          mismatched = !set.runs().get(bindingIndex++).matchesBindings(current.pattern);
          continue;
        }
        var run = set.runs().get(runIndex);
        if (itemRow < run.outputRowCount("outputs")) {
          var claim = run.outputClaim(itemRow++, level.registryAccess());
          itemTotals.add(claim.key(), claim.count(), claim.ignoreOutputCheck());
          outputPorts.add(new RequiredPort(PortKind.ITEM_OUTPUT, claim.key().selector()));
        } else if (fluidRow < run.outputRowCount("fluid_outputs")) {
          var requirement = run.fluidOutputRequirement(fluidRow++, level.registryAccess());
          var fluid = requirement.stack();
          fluidTotals.add(
              new FluidResourceKey(fluid, level.registryAccess()).withSelector(run.selectors().fluidOutput()),
              (long) fluid.getAmount(),
              requirement.ignoreOutputCheck());
          outputPorts.add(new RequiredPort(PortKind.FLUID_OUTPUT, run.selectors().fluidOutput()));
        } else {
          extensions.add(run, level.registryAccess());
          for (var row : run.chemicalOutputs()) {
            outputPorts.add(new RequiredPort(PortKind.CHEMICAL_OUTPUT, row.selector()));
          }
          runIndex++;
          itemRow = fluidRow = 0;
        }
      }
      if (runIndex == set.runs().size() && !claimsReady && used < budget) {
        var itemRows = itemTotals.freeze();
        if (!itemRows.isEmpty()) {
          itemClaims = new RecipePlanner.PaidOutputs<>(set.runs().getFirst().recipeId() + "/parallel", itemRows);
        }
        var fluidRows = fluidTotals.freeze();
        if (!fluidRows.isEmpty()) {
          fluidClaims = new RecipePlanner.PaidOutputs<>("fluid_outputs", fluidRows);
        }
        claimsReady = true;
        used++;
      }
      return used;
    }

    void captureSpaces(RecipeExecutionTicket current, ServerLevel level) {
      ticket = current;
      var available = changedParts.isEmpty() ? current : current
          .forOutputParts(port -> !changedParts.contains(port.partId()));
      extensionTicket = extensions.requiresAllOutputPorts() ? current : available
          .forOutputParts(port -> port.kind() == PortKind.CHEMICAL_OUTPUT);
      items = itemClaims == null ? null : new ItemOutputLayout.Capture(available, itemTotals.freeze().keys());
      fluids = fluidClaims == null ? null : new FluidOutputLayout.Capture(available, level.registryAccess());
      itemLayout = null;
      itemPlanner = null;
      fluidPlanner = null;
      resourcePlanner = null;
      placementProof = null;
    }

    int advanceSpaces(int budget) {
      int used = 0;
      if (items != null) {
        used += items.advance(budget);
        if (!items.complete() || used == budget) {
          return used;
        }
        itemLayout = items.layout();
        itemPlanner = itemClaims.prepare(itemLayout.snapshot());
        items = null;
        used++;
      }
      if (fluids != null) {
        used += fluids.advance(budget - used);
        if (!fluids.complete() || used == budget) {
          return used;
        }
        fluidPlanner = fluids.layout().planPaidOutputs(fluidClaims);
        fluids = null;
        used++;
      }
      if (resourcePlanner == null && used < budget) {
        resourcePlanner = extensions.plan(extensionTicket);
        used++;
      }
      return used;
    }

    Result retryChanged() {
      changedParts.clear();
      if (!extensions.requiresAllOutputPorts()) {
        for (var port : ticket.ports()) {
          if (!port.matches(ticket.level())) {
            changedParts.add(port.partId());
          }
        }
      }
      ticket = null;
      return Result.STALE;
    }

    Result blocked() {
      // An unavailable stable subset cannot prove that the full domain is blocked.
      if (!changedParts.isEmpty()) {
        changedParts.clear();
        ticket = null;
        return Result.PLANNING;
      }
      if (!ticket.dependenciesCurrent()) {
        ticket = null;
      }
      return Result.BLOCKED;
    }

    Set<RequiredPort> discardedOutputPorts() {
      var roles = new HashSet<RequiredPort>();
      if (itemPlanner != null) {
        itemPlanner.discardedOverflow()
            .forEach(key -> roles.add(new RequiredPort(PortKind.ITEM_OUTPUT, key.selector())));
      }
      if (fluidPlanner != null) {
        fluidPlanner.discardedOverflow()
            .forEach(key -> roles.add(new RequiredPort(PortKind.FLUID_OUTPUT, key.selector())));
      }
      resourcePlanner.addDiscardedOutputPorts(roles);
      return roles;
    }

    RecipeExecutionTicket commitTicket(Set<String> items, Set<String> fluids, Set<RequiredPort> overflow) {
      if (extensions.requiresAllOutputPorts()) {
        return ticket;
      }
      return ticket.forOutputParts(
          port -> overflow.contains(new RequiredPort(port.kind(), port.selector())) || switch (port.kind()) {
            case ITEM_OUTPUT -> items.contains(port.partId());
            case FLUID_OUTPUT -> fluids.contains(port.partId());
            case CHEMICAL_OUTPUT -> !changedParts.contains(port.partId());
            default -> false;
          });
    }
  }

  private final Map<String, Work> workByThread = new HashMap<>();
  private final Map<String, Attempt> attempts = new HashMap<>();
  private final HashSet<String> activeThreads = new HashSet<>();

  private record Attempt(long tick, Result result) {
  }

  public Result advance(FactoryControllerBlockEntity factory, String threadId, FactoryRunSet set) {
    if (!(factory.getLevel() instanceof ServerLevel level)
        || !level.getServer().isSameThread()
        || UpgradeLifecyclePublisher.isDispatchingAny()) {
      throw new IllegalStateException("Factory output planning outside server thread");
    }
    if (UpgradeLifecyclePublisher.isDispatchingAny()) {
      return Result.STALE;
    }
    var previous = attempts.get(threadId);
    if (previous != null && previous.tick() == level.getGameTime()) {
      return previous.result() == Result.COMMITTED ? Result.STALE : previous.result();
    }
    if (!activeThreads.add(threadId)) {
      return Result.STALE;
    }
    try {
      var result = advanceOnce(factory, threadId, set, level);
      attempts.put(threadId, new Attempt(level.getGameTime(), result));

      return result;
    } catch (RuntimeException failure) {
      workByThread.remove(threadId);
      attempts.put(threadId, new Attempt(level.getGameTime(), Result.STALE));
      factory.runCoordinator().warnFailure(threadId, "output planning", factory.getBlockPos(), failure);

      return Result.STALE;
    } finally {
      activeThreads.remove(threadId);
    }
  }

  private Result advanceOnce(FactoryControllerBlockEntity factory, String threadId, FactoryRunSet set, ServerLevel level) {
    if (set == null || factory.runSet(threadId) != set || !set.allPendingOutput()) {
      workByThread.remove(threadId);
      return Result.STALE;
    }
    var registration = factory.registration();
    if (registration == null || !factory.canCommitStructure()) {
      workByThread.remove(threadId);
      return Result.STALE;
    }
    var work = workByThread.get(threadId);
    if (work == null || work.set != set) {
      work = new Work(set);
      workByThread.put(threadId, work);
    }

    int budget = MachineConfig.FACTORY_OUTPUT_WORK.get();
    try {
      budget -= work.captureClaims(level, registration, budget);
      if (work.mismatched) {
        return Result.STALE;
      }
      if (work.bindingIndex < set.runs().size() || !work.claimsReady) {
        return Result.PLANNING;
      }
      if (work.ticket != null && !work.extensionTicket.dependenciesCurrent()) {
        return work.retryChanged();
      }
      if (work.ticket == null) {
        var ticket = RecipeResourceCapture
            .captureOutputs(factory, work.extensions.requiresAllOutputPorts() ? null : work.outputPorts).orElse(null);
        if (ticket == null) {
          return Result.STALE;
        }
        work.captureSpaces(ticket, level);
      }
      budget -= work.advanceSpaces(budget);
      if (work.resourcePlanner == null) {
        return Result.PLANNING;
      }
    } catch (RuntimeException failure) {
      workByThread.remove(threadId);
      factory.runCoordinator().warnFailure(threadId, "output preparation", factory.getBlockPos(), failure);
      return Result.BLOCKED;
    }
    RecipePlan<ItemResourceKey> itemPlacement = null;
    if (work.fluidPlanner != null && work.fluidPlanner.status() == RecipePlanner.Status.BUDGET_EXHAUSTED) {
      var fluidResult = work.fluidPlanner.advance(budget);
      budget -= fluidResult.workUsed();
    }
    if (work.fluidPlanner != null && work.fluidPlanner.status() == RecipePlanner.Status.NO_MATCH) {
      return work.blocked();
    }
    if (work.fluidPlanner != null && work.fluidPlanner.status() == RecipePlanner.Status.BUDGET_EXHAUSTED) {
      return Result.PLANNING;
    }
    if (work.itemPlanner != null) {
      // Item and chemical planning use the same remaining owner budget.
      var itemResult = work.itemPlanner.advance(budget);
      if (itemResult.status() == RecipePlanner.Status.NO_MATCH) {
        return work.blocked();
      }
      if (itemResult.status() == RecipePlanner.Status.BUDGET_EXHAUSTED) {
        return Result.PLANNING;
      }
      itemPlacement = itemResult.plan().orElseThrow();
      budget -= itemResult.workUsed();
    }
    if (work.resourcePlanner.status() != RecipePlanner.Status.SELECTED) {
      work.resourcePlanner.advance(budget);
    }
    if (work.resourcePlanner.status() == RecipePlanner.Status.NO_MATCH) {
      return work.blocked();
    }
    if (work.resourcePlanner.status() == RecipePlanner.Status.BUDGET_EXHAUSTED) {
      return Result.PLANNING;
    }

    if (work.placementProof == null) {
      var overflow = work.discardedOutputPorts();
      for (var port : work.ticket.ports()) {
        if (work.changedParts.contains(port.partId())
            && overflow.contains(new RequiredPort(port.kind(), port.selector()))) {
          // An omitted matching tank or bus might still accept this overflow.
          work.changedParts.clear();
          work.ticket = null;
          return Result.PLANNING;
        }
      }
      var itemTargets = itemPlacement == null ? Set.<String>of() : work.itemLayout.targetParts(itemPlacement);
      var fluidTargets = work.fluidPlanner == null ? Set.<String>of() : work.fluidPlanner.targetParts();
      work.placementProof = work.commitTicket(itemTargets, fluidTargets, overflow);
    }
    if (!work.placementProof.dependenciesCurrent()) {
      return work.retryChanged();
    }
    var runtime = factory.upgradeRuntimeWork().prepare(UpgradeRuntimeData.Phase.FINISHED, threadId, set.runs());
    switch (runtime.status()) {
      case PENDING -> {
        return Result.UPGRADE_PENDING;
      }
      case INSUFFICIENT -> {
        return Result.WAITING_UPGRADE;
      }
      case ERROR, INVALID -> {
        return Result.UPGRADE_ERROR;
      }
      case READY -> {
      }
    }
    Map<String, NonNullList<ItemStack>> replacements = itemPlacement == null ? Map.of() : work.itemLayout
        .prepare(itemPlacement);
    var additions = work.fluidPlanner == null ? Map.<String, FluidStack>of() : work.fluidPlanner.additions();
    var checked = work.placementProof.currentForCommit();
    if (checked.isEmpty()) {
      return work.retryChanged();
    }
    if (factory.runSet(threadId) != set || !logicalPortsMatch(factory, set)) {
      workByThread.remove(threadId);
      return Result.STALE;
    }
    var additionalResources = work.resourcePlanner.prepared(checked.orElseThrow());
    // Finish all output copies before checking the upgrade bus and output
    // inventories.
    // A component codec can edit either inventory without advancing its revision.
    if (!runtime.current()) {
      return Result.STALE;
    }
    if (!additionalResources.current() || !runtime.versionsCurrent()) {
      workByThread.remove(threadId);
      return Result.STALE;
    }
    var itemLeases = new LinkedHashMap<String, MachinePortBlockEntity.ItemStartLease>();
    var fluidLeases = new LinkedHashMap<String, MachinePortBlockEntity.FluidStartLease>();
    var upgradeLeases = new ArrayList<UpgradeBusBlockEntity.StartCostLease>();
    PatternInputTransaction patternRelease;
    try {
      patternRelease = PatternInputTransaction.release(factory, set.runs());
    } catch (RuntimeException unavailable) {
      return Result.STALE;
    }
    var resourceTransaction = new RecipeResourceTransaction();
    var itemResources = resourceTransaction.phase();
    var fluidResources = resourceTransaction.phase();
    resourceTransaction.phase().track(upgradeLeases, UpgradeBusBlockEntity.StartCostLease::resolve);
    resourceTransaction.phase().enlist(additionalResources);
    resourceTransaction.phase().enlist(patternRelease);
    boolean committed = false;
    try {
      patternRelease.acquire();
      runtime.acquireInto(upgradeLeases);
      additionalResources.acquire();
      for (var port : checked.orElseThrow().ports()) {
        if (replacements.containsKey(port.partId())) {
          itemLeases.put(
              port.partId(),
              itemResources.enlist(
                  port.identity().acquireItemStart(port.revision()),
                  MachinePortBlockEntity.ItemStartLease::resolve));
        }
        if (additions.containsKey(port.partId())) {
          fluidLeases.put(
              port.partId(),
              fluidResources.enlist(
                  port.identity().acquireFluidOutputStart(port.revision(), port.tankIndex()),
                  MachinePortBlockEntity.FluidStartLease::resolve));
        }
      }
      for (var replacement : replacements.entrySet()) {
        var lease = itemLeases.get(replacement.getKey());
        if (lease == null) {
          return Result.STALE;
        }
        lease.replacePrepared(replacement.getValue());
      }
      for (var addition : additions.entrySet()) {
        var lease = fluidLeases.get(addition.getKey());
        if (lease == null || !lease.fillExact(addition.getValue())) {
          return Result.STALE;
        }
      }
      if (!factory.runState().removeRunSetIfSame(threadId, set)) {
        return Result.STALE;
      }
      committed = true;
    } catch (RuntimeException failure) {
      factory.runCoordinator().warnFailure(threadId, "output commit", factory.getBlockPos(), failure);
      workByThread.remove(threadId);
      return Result.STALE;
    } finally {
      resourceTransaction.resolve(committed);
    }
    patternRelease.publish();
    additionalResources.publish();
    workByThread.remove(threadId);
    factory.upgradeRuntimeWork().clear(UpgradeRuntimeData.Phase.FINISHED, threadId);
    UpgradeStartCosts.publish(factory, upgradeLeases);
    factory.setChanged();
    UpgradeLifecyclePublisher.recipeFinished(factory, threadId, set.runs());
    itemLeases.values().forEach(FactoryOutputEngine::publishItem);
    fluidLeases.values().forEach(FactoryOutputEngine::publishFluid);

    return Result.COMMITTED;
  }

  public void clear() {
    workByThread.clear();
    attempts.clear();
  }

  public void invalidate() {
    workByThread.clear();
  }

  private static boolean logicalPortsMatch(FactoryControllerBlockEntity factory, FactoryRunSet set) {
    var registration = factory.registration();
    if (registration == null || !factory.canCommitStructure()) {
      return false;
    }
    return set.runs().stream().allMatch(run -> run.matchesBindings(registration.pattern));
  }

  private static void publishItem(MachinePortBlockEntity.ItemStartLease lease) {
    try {
      lease.publish();
    } catch (RuntimeException failure) {
      ModularMachine.LOGGER.warn("Factory item output notification failed", failure);
    }
  }

  private static void publishFluid(MachinePortBlockEntity.FluidStartLease lease) {
    try {
      lease.publish();
    } catch (RuntimeException failure) {
      ModularMachine.LOGGER.warn("Factory fluid output notification failed", failure);
    }
  }
}
