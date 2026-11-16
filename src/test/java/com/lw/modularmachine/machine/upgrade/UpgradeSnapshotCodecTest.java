package com.lw.modularmachine.machine.upgrade;

import com.lw.modularmachine.api.upgrade.UpgradeSnapshot;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UpgradeSnapshotCodecTest {
  @Test
  void roundTripsAllUpgradeFields() {
    var snapshot = new UpgradeSnapshot(7, 12, 3, 2, 4, 3, 5, 4);
    assertEquals(snapshot, UpgradeSnapshotCodec.read(UpgradeSnapshotCodec.write(snapshot)));
  }

  @Test
  void rejectsIncompleteSnapshot() {
    assertThrows(IllegalArgumentException.class, () -> UpgradeSnapshotCodec.read(new CompoundTag()));
  }
}
