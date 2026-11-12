package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.api.upgrade.FactoryUpgradeSnapshot;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeEntry;
import java.util.Collections;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FactoryUpgradeCatalogTest {
  @Test
  void compilesSlotEntriesFromOneParallelBaseline() {
    var speed = entry("speed", 0, 2, 1, 1, 1, 1, 1);
    var parallel = entry("parallel", 3, 1, 1, 1, 1, 1, 1);
    var catalog = FactoryUpgradeCatalog.compile(List.of(speed, parallel), 9);
    assertEquals(2, catalog.entries().size());
    assertEquals(4, catalog.snapshot().parallelLimit());
    assertEquals(2, catalog.snapshot().speedNumerator());
    assertEquals(9, catalog.snapshot().revision());
  }

  @Test
  void emptyCatalogPublishesIdentityAndRejectsInvalidRevisions() {
    var empty = FactoryUpgradeCatalog.compile(List.of(), 7);
    assertEquals(List.of(), empty.entries());
    assertEquals(FactoryUpgradeSnapshot.identity(7), empty.snapshot());
    assertThrows(IllegalArgumentException.class, () -> FactoryUpgradeCatalog.compile(List.of(), -1));
  }

  @Test
  void rejectsOversizedSlotCatalog() {
    var entry = entry("parallel", 0, 1, 1, 1, 1, 1, 1);
    assertThrows(
        IllegalArgumentException.class,
        () -> FactoryUpgradeCatalog.compile(Collections.nCopies(FactoryUpgradeCatalog.MAX_SLOTS + 1, entry), 1));
  }

  private static FactoryUpgradeEntry entry(String path, int parallel, int speedNum, int speedDen, int energyNum, int energyDen, int fluidNum, int fluidDen) {
    return new FactoryUpgradeEntry(
        ResourceLocation.fromNamespaceAndPath("mmcece", path),
        parallel,
        speedNum,
        speedDen,
        energyNum,
        energyDen,
        fluidNum,
        fluidDen);
  }
}
