package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.api.upgrade.FactoryUpgradeSnapshot;

import net.minecraft.resources.ResourceLocation;

/**
 * Read-only start configuration, independent of factory scheduling and paid run
 * storage.
 */
public record UpgradeStartState(
    String threadId,
    ResourceLocation machineId,
    boolean enabled,
    ResourceLocation lockedRecipe,
    FactoryUpgradeSnapshot upgradeSnapshot) {
}
