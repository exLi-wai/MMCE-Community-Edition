package com.lw.modularmachine.machine;

import java.util.Locale;

public enum EmberHatchTier {
  TINY(400),
  SMALL(800),
  MEDIUM(1600),
  REINFORCED(3200),
  BIG(6400),
  HUGE(9600),
  LUDICROUS(12800),
  EXTREME(16000);

  private final int capacity;

  EmberHatchTier(int capacity) {
    this.capacity = capacity;
  }

  public String path() {
    return name().toLowerCase(Locale.ROOT);
  }

  public int capacity() {
    return capacity;
  }

  public boolean acceptsVolatile() {
    return capacity >= 3200;
  }
}
