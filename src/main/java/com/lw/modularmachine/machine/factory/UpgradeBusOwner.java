package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import com.lw.modularmachine.machine.recipe.FrozenItemRun;
import com.lw.modularmachine.machine.structure.StructureService;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * Main-thread owner contract; neither ownership nor a displayed catalog grants
 * a resource lease.
 */
public interface UpgradeBusOwner extends StructureService.StructureSubject {
  BlockPos getBlockPos();

  Level getLevel();

  boolean isRemoved();

  ResourceLocation machineId();

  boolean canCommitStructure();

  boolean containsUpgradeBusPart(BlockPos position);

  boolean publishUpgradeBus(UpgradeBusBlockEntity bus);

  void withdrawUpgradeBus(UpgradeBusBlockEntity bus);

  FactoryUpgradeRestrictions.Result upgradeRestrictionStatus();

  List<UpgradeBusBlockEntity> upgradeCallbackBuses();

  UpgradeStartState upgradeStartState(String threadId);

  boolean upgradeRunIdle(String threadId);

  List<BlockPos> upgradeBusPositions();

  List<FrozenItemRun> upgradeRuns(String threadId);

  FactoryUpgradeRuntimeWork upgradeRuntimeWork();
}
