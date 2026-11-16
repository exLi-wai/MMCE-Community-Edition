package com.lw.modularmachine.machine.upgrade;

import com.lw.modularmachine.api.upgrade.UpgradeSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UpgradeSnapshotTest {
  @Test
  void identityAndBoundedScalingAreDeterministic() {
    var identity = UpgradeSnapshot.identity(3);
    assertEquals(20, identity.durationFor(20));
    var faster = new UpgradeSnapshot(4, 8, 2, 1, 1, 2, 1, 2);
    assertEquals(10, faster.durationFor(20));
    assertEquals(5, faster.energyPerTick(10));
    assertEquals(5, faster.fluidPerTick(10));
    assertEquals(2, faster.stepsPerTick());
    assertEquals(1, identity.stepsPerTick());
  }

  @Test
  void rejectsInvalidUpgradeValues() {
    assertThrows(IllegalArgumentException.class, () -> UpgradeSnapshot.identity(-1));
    assertThrows(IllegalArgumentException.class, () -> new UpgradeSnapshot(0, 0, 1, 1, 1, 1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> UpgradeSnapshot.identity(0).durationFor(0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new UpgradeSnapshot(0, 1, UpgradeSnapshot.MAX_SPEED_STEPS_PER_TICK + 1, 1, 1, 1, 1, 1));
  }

  @Test
  void rejectsScalingOverflowInsteadOfWrapping() {
    var snapshot = new UpgradeSnapshot(1, 1, 1, 1, 2, 1, 2, 1);
    assertThrows(ArithmeticException.class, () -> snapshot.energyPerTick(Long.MAX_VALUE));
    assertThrows(ArithmeticException.class, () -> snapshot.fluidPerTick(Long.MAX_VALUE));
  }

  @Test
  void composesUpgradeEffectsDeterministically() {
    var composed = UpgradeSnapshot
        .compose(List.of(new UpgradeSnapshot(2, 2, 2, 1, 1, 1, 1, 1), new UpgradeSnapshot(5, 3, 3, 2, 2, 1, 1, 2)));
    assertEquals(5, composed.revision());
    assertEquals(4, composed.parallelLimit());
    assertEquals(6, composed.speedNumerator());
    assertEquals(2, composed.speedDenominator());
  }
}
