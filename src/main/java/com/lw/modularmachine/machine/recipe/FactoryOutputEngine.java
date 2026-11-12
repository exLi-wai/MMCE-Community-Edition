package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.factory.UpgradeLifecyclePublisher;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.factory.FactoryRunSet;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeRuntimeData;
import com.lw.modularmachine.machine.factory.FactoryUpgradeStartCosts;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

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
    final RecipeExecutionTicket ticket;
    final ItemOutputLayout itemLayout;
    final RecipePlanner.Session<ItemResourceKey> itemPlanner;
    final FluidOutputLayout.Planning fluidPlanner;
    final ChemicalOutputLayout.Planning chemicalPlanner;

    Work(FactoryRunSet set, RecipeExecutionTicket ticket, ServerLevel level) {
      this.set = set;
      this.ticket = ticket;
      var itemTotals = new LinkedHashMap<ItemResourceKey, Long>();
      var itemKeys = new HashSet<ItemResourceKey>();
      var fluidTotals = new LinkedHashMap<FluidResourceKey, Long>();
      for (var run : set.runs()) {
        for (var claim : run.outputClaims(level.registryAccess())) {
          itemTotals.merge(claim.key(), claim.count(), Math::addExact);
          itemKeys.add(claim.key());
        }
        for (var fluid : run.fluidOutputClaims(level.registryAccess())) {
          fluidTotals.merge(
              new FluidResourceKey(fluid, level.registryAccess()).withSelector(run.selectors().fluidOutput()),
              (long) fluid.getAmount(),
              Math::addExact);
        }
      }
      var itemClaims = itemTotals.entrySet().stream()
          .map(entry -> new RecipeCandidate.Output<>(entry.getKey(), entry.getValue())).toList();
      itemLayout = itemClaims.isEmpty() ? null : new ItemOutputLayout(ticket, itemKeys);
      itemPlanner = itemLayout == null ? null : RecipePlanner.prepareOutputs(
          itemLayout.snapshot,
          RecipeCandidate.outputOnly(set.runs().getFirst().recipeId() + "/parallel", itemClaims));
      var fluidClaims = fluidTotals.entrySet().stream()
          .map(entry -> new RecipeCandidate.Output<>(entry.getKey(), entry.getValue())).toList();
      fluidPlanner = new FluidOutputLayout(ticket, level.registryAccess()).planClaims(fluidClaims);
      chemicalPlanner = new ChemicalOutputLayout(ticket).planClaims(chemicalOutputClaims(set));
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
      ModularMachine.LOGGER.warn("Factory output planning failed at {}", factory.getBlockPos(), failure);

      return Result.STALE;
    } finally {
      activeThreads.remove(threadId);
    }
  }

  private Result advanceOnce(FactoryControllerBlockEntity factory, String threadId, FactoryRunSet set, ServerLevel level) {
    if (set == null
        || factory.runSet(threadId) != set
        || set.runs().isEmpty()
        || set.runs().stream().anyMatch(run -> !run.pendingOutput())) {
      workByThread.remove(threadId);
      return Result.STALE;
    }
    if (!logicalPortsMatch(factory, set)) {
      workByThread.remove(threadId);
      return Result.STALE;
    }
    var work = workByThread.get(threadId);
    if (work == null || work.set != set || !work.ticket.dependenciesCurrent()) {
      workByThread.remove(threadId);
      var ticket = RecipeExecutionTicket.captureOutputs(factory).orElse(null);
      if (ticket == null) {
        return Result.STALE;
      }
      try {
        work = new Work(set, ticket, level);
        workByThread.put(threadId, work);
      } catch (RuntimeException failure) {
        ModularMachine.LOGGER.warn("Factory output planning failed at {}", factory.getBlockPos(), failure);
        return Result.BLOCKED;
      }
    }

    int budget = MachineConfig.FACTORY_OUTPUT_WORK.get();
    Map<String, NonNullList<ItemStack>> replacements = Map.of();
    if (work.fluidPlanner.status() == RecipePlanner.Status.BUDGET_EXHAUSTED) {
      var fluidResult = work.fluidPlanner.advance(budget);
      budget -= fluidResult.workUsed();
    }
    if (work.fluidPlanner.status() == RecipePlanner.Status.NO_MATCH) {
      return Result.BLOCKED;
    }
    if (work.fluidPlanner.status() == RecipePlanner.Status.BUDGET_EXHAUSTED) {
      return Result.PLANNING;
    }
    if (work.itemPlanner != null) {
      // Item and chemical planning use the same remaining owner budget.
      var itemResult = work.itemPlanner.advance(budget);
      if (itemResult.status() == RecipePlanner.Status.NO_MATCH) {
        return Result.BLOCKED;
      }
      if (itemResult.status() == RecipePlanner.Status.BUDGET_EXHAUSTED) {
        return Result.PLANNING;
      }
      replacements = work.itemLayout.prepare(itemResult.plan().orElseThrow());
      budget -= itemResult.workUsed();
    }
    if (work.chemicalPlanner.status() == RecipePlanner.Status.BUDGET_EXHAUSTED) {
      work.chemicalPlanner.advance(budget);
    }
    if (work.chemicalPlanner.status() == RecipePlanner.Status.NO_MATCH) {
      return Result.BLOCKED;
    }
    if (work.chemicalPlanner.status() == RecipePlanner.Status.BUDGET_EXHAUSTED) {
      return Result.PLANNING;
    }

    var runtime = factory.upgradeRuntimeWork().prepare(FactoryUpgradeRuntimeData.Phase.FINISHED, threadId, set.runs());
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
    if (!runtime.current()) {
      return Result.STALE;
    }
    var checked = work.ticket.refreshIfUnchanged().filter(RecipeCommitGuard::validate);
    if (checked.isEmpty()
        || factory.runSet(threadId) != set
        || !logicalPortsMatch(factory, set)
        || !runtime.versionsCurrent()) {
      workByThread.remove(threadId);
      return Result.STALE;
    }
    var additions = work.fluidPlanner.additions();
    var chemicals = ChemicalTransactions.outputs(checked.orElseThrow(), work.chemicalPlanner.additions());
    var itemLeases = new LinkedHashMap<String, MachinePortBlockEntity.ItemStartLease>();
    var fluidLeases = new LinkedHashMap<String, MachinePortBlockEntity.FluidStartLease>();
    var upgradeLeases = new ArrayList<UpgradeBusBlockEntity.StartCostLease>();
    boolean committed = false;
    try {
      runtime.acquireInto(upgradeLeases);
      chemicals.acquire();
      for (var port : checked.orElseThrow().ports()) {
        if (replacements.containsKey(port.partId())) {
          itemLeases.put(port.partId(), port.identity().acquireItemStart(port.revision()));
        }
        if (additions.containsKey(port.partId())) {
          fluidLeases.put(port.partId(), port.identity().acquireFluidOutputStart(port.revision(), port.tankIndex()));
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
      if (!factory.removeRunSetIfSame(threadId, set)) {
        return Result.STALE;
      }
      committed = true;
    } catch (RuntimeException failure) {
      ModularMachine.LOGGER.warn("Factory output commit failed at {}", factory.getBlockPos(), failure);
      workByThread.remove(threadId);
      return Result.STALE;
    } finally {
      for (var lease : itemLeases.values()) {
        lease.resolve(committed);
      }
      for (var lease : fluidLeases.values()) {
        lease.resolve(committed);
      }
      for (var lease : upgradeLeases) {
        lease.resolve(committed);
      }
      chemicals.resolve(committed);
    }
    chemicals.publish();
    workByThread.remove(threadId);
    factory.upgradeRuntimeWork().clear(FactoryUpgradeRuntimeData.Phase.FINISHED, threadId);
    FactoryUpgradeStartCosts.publish(factory, upgradeLeases);
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

  static List<RecipeCandidate.Output<ChemicalResourceKey>> chemicalOutputClaims(FactoryRunSet set) {
    // Collapse repeated run outputs before the planner applies its demand-row
    // limit.
    var totals = new LinkedHashMap<ChemicalResourceKey, Long>();
    for (var run : set.runs()) {
      for (var output : run.chemicalOutputs()) {
        totals.merge(new ChemicalResourceKey(output.chemical(), output.selector()), output.amount(), Math::addExact);
      }
    }

    return totals.entrySet().stream().map(entry -> new RecipeCandidate.Output<>(entry.getKey(), entry.getValue()))
        .toList();
  }

  private static boolean logicalPortsMatch(FactoryControllerBlockEntity factory, FactoryRunSet set) {
    var registration = factory.registration();
    if (registration == null || !factory.canCommitStructure()) {
      return false;
    }
    return set.runs().stream().allMatch(run -> run.matchesBindings(registration.pattern.definition));
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
