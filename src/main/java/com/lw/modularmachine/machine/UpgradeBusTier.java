package com.lw.modularmachine.machine;

import java.util.Locale;

public enum UpgradeBusTier {
  NORMAL(3),
  REINFORCED(6),
  ELITE(9),
  SUPER(12),
  ULTIMATE(18);

  private final int slots;

  UpgradeBusTier(int slots) {
    this.slots = slots;
  }

  public int slots() {
    return slots;
  }

  public String path() {
    return name().toLowerCase(Locale.ROOT);
  }
}
