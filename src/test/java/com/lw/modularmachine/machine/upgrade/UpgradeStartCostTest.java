package com.lw.modularmachine.machine.upgrade;

import com.lw.modularmachine.api.upgrade.UpgradeStartCost;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpgradeStartCostTest {
  @Test
  void noCostAndEachSinglePaymentKindAreExplicit() {
    assertFalse(new UpgradeStartCost(0, 0).present());
    assertTrue(new UpgradeStartCost(1, 0).present());
    assertTrue(new UpgradeStartCost(0, 1).present());
  }

  @Test
  void rejectsConflictingNegativeAndOversizedPaymentKinds() {
    for (int[] values : new int[][]{{-1, 0}, {0, -1}, {1, 1}, {1_000_001, 0}, {0, 1_000_001}, {Integer.MAX_VALUE, 0}}) {
      assertThrows(IllegalArgumentException.class, () -> new UpgradeStartCost(values[0], values[1]));
    }
  }
}
