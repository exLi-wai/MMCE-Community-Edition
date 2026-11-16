package com.lw.modularmachine.machine.upgrade;

import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.api.upgrade.UpgradeSnapshot;
import net.minecraft.resources.ResourceLocation;

/**
 * Read-only start configuration, independent of factory scheduling and paid run
 * storage.
 */
@ApiStatus.Internal
public record UpgradeStartState(
    String threadId,
    ResourceLocation machineId,
    boolean enabled,
    ResourceLocation lockedRecipe,
    UpgradeSnapshot upgradeSnapshot) {
}
