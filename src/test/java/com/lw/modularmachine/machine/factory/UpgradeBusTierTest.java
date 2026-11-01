package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.machine.UpgradeBusTier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UpgradeBusTierTest {
  @Test
  void keepsLegacySlotTiers() {
    assertEquals(3, UpgradeBusTier.NORMAL.slots());
    assertEquals(6, UpgradeBusTier.REINFORCED.slots());
    assertEquals(9, UpgradeBusTier.ELITE.slots());
    assertEquals(12, UpgradeBusTier.SUPER.slots());
    assertEquals(18, UpgradeBusTier.ULTIMATE.slots());
  }
}
