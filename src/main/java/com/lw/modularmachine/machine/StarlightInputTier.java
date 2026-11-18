package com.lw.modularmachine.machine;

import java.util.Locale;

public enum StarlightInputTier {
  BASIC(2_000),
  TRAIT(8_000),
  BRILLIANT(32_000);

  private final int capacity;

  StarlightInputTier(int capacity) {
    this.capacity = capacity;
  }

  public String path() {
    return name().toLowerCase(Locale.ROOT);
  }

  public int capacity() {
    return capacity;
  }
}
