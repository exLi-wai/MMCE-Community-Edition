package com.lw.modularmachine.machine.upgrade;

import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import com.lw.modularmachine.machine.recipe.FrozenRecipeRun;
import com.lw.modularmachine.machine.structure.StructureService;
import java.util.List;
import java.util.Collections;
import java.util.IdentityHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * Main-thread owner contract; neither ownership nor a displayed catalog grants
 * a resource lease.
 */
@ApiStatus.Internal
public interface UpgradeBusOwner extends StructureService.StructureSubject {
  BlockPos getBlockPos();

  Level getLevel();

  boolean isRemoved();

  ResourceLocation machineId();

  boolean canCommitStructure();

  boolean containsUpgradeBusPart(BlockPos position);

  boolean publishUpgradeBus(UpgradeBusBlockEntity bus);

  void withdrawUpgradeBus(UpgradeBusBlockEntity bus);

  UpgradeRestrictions.Result upgradeRestrictionStatus();

  List<UpgradeBusBlockEntity> upgradeCallbackBuses();

  UpgradeStartState upgradeStartState(String threadId);

  boolean upgradeRunIdle(String threadId);

  List<BlockPos> upgradeBusPositions();

  List<FrozenRecipeRun> upgradeRuns(String threadId);

  default boolean containsUpgradeRuns(String threadId, List<FrozenRecipeRun> expected) {
    var live = upgradeRuns(threadId);
    if (expected.size() == 1) {
      return live.contains(expected.getFirst());
    }
    if (live.equals(expected)) {
      return true;
    }
    var identities = Collections.newSetFromMap(new IdentityHashMap<FrozenRecipeRun, Boolean>());
    identities.addAll(live);
    return identities.containsAll(expected);
  }

  UpgradeRuntimeWork upgradeRuntimeWork();
}
