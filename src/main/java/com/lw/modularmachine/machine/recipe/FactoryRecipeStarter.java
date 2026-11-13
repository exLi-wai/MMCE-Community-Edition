package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.recipe.search.RecipeResourceRequirements;
import com.lw.modularmachine.machine.factory.UpgradeLifecyclePublisher;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity.StartCostLease;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.factory.FactoryThreadState;
import com.lw.modularmachine.machine.factory.FactoryUpgradeStartCosts;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/** Atomic item-input commit for one independent factory channel. */
public final class FactoryRecipeStarter {
  public enum Result {
    STARTED,
    BUSY,
    STALE,
    UNMANAGED_RECIPE,
    FAILED,
    CHECK_REJECTED,
    CHECK_PENDING,
    CHECK_ERROR,
    UPGRADE_COST_MISSING
  }

  private FactoryRecipeStarter() {
  }

  public static Result start(FactoryControllerBlockEntity factory, String threadId, RecipeStartPlan plan) {
    if (factory == null || threadId == null || plan == null || !factory.beginRunStart(threadId)) {
      return Result.BUSY;
    }
    boolean committed = false;
    var leases = new ArrayList<MachinePortBlockEntity.ItemStartLease>();
    var fluidLeases = new HashMap<String, MachinePortBlockEntity.FluidStartLease>();
    ChemicalTransactions.Batch chemicals = null;
    var upgradeLeases = new ArrayList<StartCostLease>();
    try {
      var nativeRecipe = MachineRecipeService.definitionForStart(factory, plan);
      if (nativeRecipe.isEmpty()) {
        return Result.UNMANAGED_RECIPE;
      }
      int checkBudget = MachineConfig.FACTORY_SEARCH_WORK.get();
      var admission = plan.advanceFactoryChecks(factory, threadId, checkBudget);
      checkBudget -= admission.workUsed();
      switch (admission.status()) {
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
      var checked = plan.checkedTicket();
      if (checked.isEmpty()) {
        return Result.STALE;
      }
      var ticket = checked.orElseThrow();
      if (RecipeResourceRequirements.missing(ticket, nativeRecipe.orElseThrow()).isPresent()) {
        return Result.STALE;
      }
      var ports = new HashMap<String, RecipeExecutionTicket.PortSnapshot>();
      for (var port : ticket.ports()) {
        ports.put(port.partId(), port);
      }
      var replacements = new HashMap<String, NonNullList<ItemStack>>();
      for (var deduction : plan.deductions()) {
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
        if (slot >= contents.size()
            || !deduction.key().matches(contents.get(slot))
            || contents.get(slot).getCount() < deduction.count()) {
          return Result.STALE;
        }
        contents.set(slot, contents.get(slot).copyWithCount(contents.get(slot).getCount() - deduction.count()));
      }
      var fluidDebits = planFluidDebits(
          ticket,
          nativeRecipe.orElseThrow().fluidInputs(),
          nativeRecipe.orElseThrow().selectors().fluidInput());
      if (fluidDebits == null) {
        return Result.STALE;
      }
      chemicals = ChemicalTransactions.inputs(ticket, nativeRecipe.orElseThrow().chemicals().inputs());
      if (chemicals == null) {
        return Result.STALE;
      }
      var startCosts = plan.prepareFactoryStartup(factory, threadId, 1);
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
      var upgrades = factory.scheduler().channels().stream().filter(thread -> thread.threadId().equals(threadId))
          .map(FactoryThreadState::upgradeSnapshot).findFirst().orElseThrow();
      var run = FrozenItemRun.freeze(
          ResourceLocation.parse(plan.recipe().id()),
          nativeRecipe.orElseThrow(),
          ticket.definitionEpoch(),
          factory.getLevel().registryAccess(),
          ticket.allPorts(),
          factory.getLevel().getGameTime(),
          upgrades,
          ticket.inputGroup(),
          1,
          ticket.dynamicLengths());
      if (!plan.factoryAdmissionCurrent(factory, threadId)) {
        return Result.STALE;
      }
      startCosts.acquireInto(upgradeLeases);
      chemicals.acquire();
      for (var port : ticket.ports()) {
        if (port.kind().resource() == PortKind.Resource.ITEM) {
          var lease = port.identity().acquireItemStart(port.revision());
          var replacement = replacements.get(port.partId());
          if (replacement != null) {
            lease.replacePrepared(replacement);
          }
          leases.add(lease);
        } else if (port.kind() == PortKind.FLUID_INPUT && !fluidDebits.isEmpty()) {
          fluidLeases.put(port.partId(), port.identity().acquireFluidStart(port.revision(), port.tankIndex()));
        }
      }
      for (var debit : fluidDebits) {
        if (!fluidLeases.get(debit.partId()).drainExact(debit.amount())) {
          return Result.STALE;
        }
      }
      factory.installRun(threadId, run);
      committed = true;
      return Result.STARTED;
    } catch (RuntimeException failure) {
      ModularMachine.LOGGER.warn("Factory recipe start failed at {}", factory.getBlockPos(), failure);
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
      if (chemicals != null) {
        chemicals.resolve(committed);
      }
      factory.finishRunStart(threadId, committed);
      if (committed) {
        chemicals.publish();
        factory.setChanged();
        FactoryUpgradeStartCosts.publish(factory, upgradeLeases);
        UpgradeLifecyclePublisher.recipeStarted(factory, threadId, factory.runSet(threadId).runs());
        leases.forEach(MachinePortBlockEntity.ItemStartLease::publish);
        fluidLeases.values().forEach(MachinePortBlockEntity.FluidStartLease::publish);
      }
    }
  }

  private record FluidDebit(String partId, FluidStack amount) {
  }

  private static List<FluidDebit> planFluidDebits(RecipeExecutionTicket ticket, List<FluidStack> requirements, String selector) {
    var reserved = new HashMap<String, Integer>();
    var result = new ArrayList<FluidDebit>();
    for (var requirement : requirements) {
      int remaining = requirement.getAmount();
      for (var port : ticket.ports()) {
        if (port.kind() != PortKind.FLUID_INPUT
            || !port.selector().equals(selector)
            || !FluidStack.isSameFluidSameComponents(port.fluid(), requirement)) {
          continue;
        }
        int already = reserved.getOrDefault(port.partId(), 0);
        int amount = Math.min(remaining, Math.max(0, port.fluid().getAmount() - already));
        if (amount > 0) {
          reserved.put(port.partId(), already + amount);
          result.add(new FluidDebit(port.partId(), requirement.copyWithAmount(amount)));
          remaining -= amount;
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
}
