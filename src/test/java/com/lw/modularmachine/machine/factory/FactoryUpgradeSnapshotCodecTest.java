package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.api.upgrade.FactoryUpgradeSnapshot;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FactoryUpgradeSnapshotCodecTest {
  @Test
  void roundTripsAllUpgradeFields() {
    var snapshot = new FactoryUpgradeSnapshot(7, 12, 3, 2, 4, 3, 5, 4);
    assertEquals(snapshot, FactoryUpgradeSnapshotCodec.read(FactoryUpgradeSnapshotCodec.write(snapshot)));
  }

  @Test
  void rejectsIncompleteSnapshot() {
    assertThrows(IllegalArgumentException.class, () -> FactoryUpgradeSnapshotCodec.read(new CompoundTag()));
  }
}
