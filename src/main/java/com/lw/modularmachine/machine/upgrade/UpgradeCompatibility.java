package com.lw.modularmachine.machine.upgrade;

import org.jetbrains.annotations.ApiStatus;
import java.util.Collections;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable legacy-compatible allow/deny rules evaluated before stack scaling.
 */
@ApiStatus.Internal
public record UpgradeCompatibility(
    Set<ResourceLocation> compatibleMachines,
    Set<ResourceLocation> incompatibleMachines) {
  public static final int MAX_MACHINE_IDS = 256;

  public UpgradeCompatibility {
    compatibleMachines = Set.copyOf(compatibleMachines);
    incompatibleMachines = Set.copyOf(incompatibleMachines);
    if ((long) compatibleMachines.size() + incompatibleMachines.size() > MAX_MACHINE_IDS
        || !Collections.disjoint(compatibleMachines, incompatibleMachines)) {
      throw new IllegalArgumentException("Upgrade machine lists overlap or exceed their supported size");
    }
  }

  public static UpgradeCompatibility unrestricted() {
    return new UpgradeCompatibility(Set.of(), Set.of());
  }

  public boolean allows(ResourceLocation machine) {
    if (machine == null) {
      return compatibleMachines.isEmpty() && incompatibleMachines.isEmpty();
    }

    return (compatibleMachines.isEmpty() || compatibleMachines.contains(machine))
        && !incompatibleMachines.contains(machine);
  }
}
