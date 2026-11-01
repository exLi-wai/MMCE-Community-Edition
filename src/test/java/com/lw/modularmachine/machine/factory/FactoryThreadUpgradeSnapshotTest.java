package com.lw.modularmachine.machine.factory;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FactoryThreadUpgradeSnapshotTest {
  @Test
  void threadStateExposesItsCurrentUpgradeRevisionAndParallelLimit() {
    var state = new FactoryThreadState(
        "thread",
        ResourceLocation.fromNamespaceAndPath("mmcece", "machine"),
        null,
        true,
        0,
        12,
        9,
        FactoryThreadState.State.IDLE,
        FactoryThreadState.Reason.NONE,
        2,
        new FactoryUpgradeSnapshot(9, 12, 1, 1, 1, 1, 1, 1));
    assertEquals(12, state.upgradeSnapshot().parallelLimit());
    assertEquals(9, state.upgradeSnapshot().revision());
  }

  @Test
  void threadCanPublishANewSnapshotOnlyWithAnAdvancedRevision() {
    var state = new FactoryThreadState(
        "thread",
        ResourceLocation.fromNamespaceAndPath("mmcece", "machine"),
        null,
        true,
        0,
        1,
        0,
        FactoryThreadState.State.IDLE,
        FactoryThreadState.Reason.NONE,
        0,
        FactoryUpgradeSnapshot.identity(0));
    var upgraded = state.withUpgradeSnapshot(new FactoryUpgradeSnapshot(1, 8, 3, 2, 2, 1, 1, 1));
    assertEquals(8, upgraded.parallelLimit());
    assertEquals(3, upgraded.upgradeSnapshot().speedNumerator());
    var restored = FactoryThreadStateCodec.read(FactoryThreadStateCodec.write(List.of(upgraded))).get(0);
    assertEquals(upgraded.upgradeSnapshot(), restored.upgradeSnapshot());
    assertThrows(
        IllegalArgumentException.class,
        () -> upgraded.withUpgradeSnapshot(new FactoryUpgradeSnapshot(1, 9, 4, 1, 1, 1, 1, 1)));
  }
}
