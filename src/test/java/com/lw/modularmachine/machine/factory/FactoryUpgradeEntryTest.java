package com.lw.modularmachine.machine.factory;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FactoryUpgradeEntryTest {
  @Test
  void convertsUpgradeDataToSnapshot() {
    var entry = new FactoryUpgradeEntry(
        ResourceLocation.fromNamespaceAndPath("mmcece", "parallel"),
        3,
        2,
        1,
        1,
        2,
        1,
        2);
    var snapshot = entry.snapshot(7);
    assertEquals(4, snapshot.parallelLimit());
    assertEquals(7, snapshot.revision());
    assertEquals(2, snapshot.speedNumerator());
  }
}
