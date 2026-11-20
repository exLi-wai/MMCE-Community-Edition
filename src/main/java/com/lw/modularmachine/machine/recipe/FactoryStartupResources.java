package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.component.ResourceType;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity.StartCostLease;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceTransaction;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecutionRegistry;
import com.lw.modularmachine.machine.upgrade.UpgradeStartCosts;
import com.lw.modularmachine.machine.upgrade.UpgradeLifecyclePublisher;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Per-start factory resources; callers own admission, deductions, run
 * installation and commit.
 */
@ApiStatus.Internal
final class FactoryStartupResources {
  private final FactoryControllerBlockEntity factory;
  private final String threadId;
  private final List<MachinePortBlockEntity.ItemStartLease> leases = new ArrayList<>();
  private final Map<String, MachinePortBlockEntity.FluidStartLease> fluidLeases = new HashMap<>();
  private RecipeResourceExecutionRegistry.Prepared additionalResources;
  private final PatternInputTransaction patterns = new PatternInputTransaction();
  private final List<StartCostLease> upgradeLeases = new ArrayList<>();
  private final RecipeResourceTransaction transaction = new RecipeResourceTransaction();
  private final RecipeResourceTransaction.Phase itemResources;
  private final RecipeResourceTransaction.Phase fluidResources;
  private final RecipeResourceTransaction.Phase additionalResourcePhase;

  FactoryStartupResources(FactoryControllerBlockEntity factory, String threadId) {
    this.factory = factory;
    this.threadId = threadId;
    transaction.phase().enlist(patterns);
    itemResources = transaction.phase();
    fluidResources = transaction.phase();
    transaction.phase().track(upgradeLeases, StartCostLease::resolve);
    additionalResourcePhase = transaction.phase();
    transaction.phase().enlist(commit -> factory.runState().finishRunStart(threadId, commit));
  }

  PatternInputTransaction patterns() {
    return patterns;
  }

  List<StartCostLease> upgradeLeases() {
    return upgradeLeases;
  }

  void enlistAdditional(RecipeResourceExecutionRegistry.Prepared prepared) {
    additionalResources = prepared;
    additionalResourcePhase.enlist(prepared);
  }

  record DeductionTarget(NonNullList<ItemStack> contents, boolean accepted) {
  }

  DeductionTarget prepareDeduction(RecipeStartPlan.Deduction deduction, Map<String, RecipePortSnapshot> ports, Map<String, NonNullList<ItemStack>> replacements) {
    var port = ports.get(deduction.slot().partId());
    if (port == null || port.kind() != PortKind.ITEM_INPUT) {
      return new DeductionTarget(null, false);
    }
    if (port.patternInput()) {
      if (!port.matchesItem(deduction.slot().slot(), deduction.key())) {
        return new DeductionTarget(null, false);
      }
      patterns.consumeItem(port, deduction.slot().slot(), deduction.count());
      return new DeductionTarget(null, true);
    }
    var contents = replacements.computeIfAbsent(deduction.slot().partId(), ignored -> port.copyItemContents());
    return new DeductionTarget(contents, true);
  }

  void acquire(RecipeExecutionTicket ticket, Map<String, NonNullList<ItemStack>> replacements, boolean acquireFluids) {
    for (var port : ticket.ports()) {
      if (!port.patternInput() && port.kind().resource() == ResourceType.ITEM) {
        var lease = itemResources
            .enlist(port.identity().acquireItemStart(port.revision()), MachinePortBlockEntity.ItemStartLease::resolve);
        var replacement = replacements.get(port.partId());
        if (replacement != null) {
          lease.replacePrepared(replacement);
        }
        leases.add(lease);
      } else if (!port.patternInput() && port.kind() == PortKind.FLUID_INPUT && acquireFluids) {
        fluidLeases.put(
            port.partId(),
            fluidResources.enlist(
                port.identity().acquireFluidStart(port.revision(), port.tankIndex()),
                MachinePortBlockEntity.FluidStartLease::resolve));
      }

    }
  }

  boolean drainFluid(String partId, FluidStack amount) {
    return fluidLeases.get(partId).drainExact(amount);
  }

  void resolve(boolean committed) {
    transaction.resolve(committed);
  }

  /** Called only after all resource phases resolve successfully. */
  void publishCommitted() {
    patterns.publish();
    additionalResources.publish();
    factory.setChanged();
    UpgradeStartCosts.publish(factory, upgradeLeases);
    UpgradeLifecyclePublisher.recipeStarted(factory, threadId, factory.runSet(threadId).runs());
    leases.forEach(MachinePortBlockEntity.ItemStartLease::publish);
    fluidLeases.values().forEach(MachinePortBlockEntity.FluidStartLease::publish);

  }
}
