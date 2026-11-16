package com.lw.modularmachine.machine.upgrade;

import com.lw.modularmachine.api.upgrade.UpgradeSnapshot;
import com.lw.modularmachine.api.upgrade.UpgradeEntry;
import java.util.Collections;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UpgradeCatalogTest {
  @Test
  void compilesSlotEntriesFromOneParallelBaseline() {
    var speed = entry("speed", 0, 2, 1, 1, 1, 1, 1);
    var parallel = entry("parallel", 3, 1, 1, 1, 1, 1, 1);
    var catalog = UpgradeCatalog.compile(List.of(speed, parallel), 9);
    assertEquals(2, catalog.entries().size());
    assertEquals(4, catalog.snapshot().parallelLimit());
    assertEquals(2, catalog.snapshot().speedNumerator());
    assertEquals(9, catalog.snapshot().revision());
  }

  @Test
  void emptyCatalogPublishesIdentityAndRejectsInvalidRevisions() {
    var empty = UpgradeCatalog.compile(List.of(), 7);
    assertEquals(List.of(), empty.entries());
    assertEquals(UpgradeSnapshot.identity(7), empty.snapshot());
    assertThrows(IllegalArgumentException.class, () -> UpgradeCatalog.compile(List.of(), -1));
  }

  @Test
  void rejectsOversizedSlotCatalog() {
    var entry = entry("parallel", 0, 1, 1, 1, 1, 1, 1);
    assertThrows(
        IllegalArgumentException.class,
        () -> UpgradeCatalog.compile(Collections.nCopies(UpgradeCatalog.MAX_SLOTS + 1, entry), 1));
  }

  private static UpgradeEntry entry(String path, int parallel, int speedNum, int speedDen, int energyNum, int energyDen, int fluidNum, int fluidDen) {
    return new UpgradeEntry(
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
