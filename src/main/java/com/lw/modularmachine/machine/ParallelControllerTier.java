package com.lw.modularmachine.machine;

import com.lw.modularmachine.config.MachineConfig;
import java.util.Locale;

public enum ParallelControllerTier {
  PIG(1, false),
  NORMAL(4),
  REINFORCED(16),
  ELITE(64),
  SUPER(256),
  ULTIMATE(512),
  TRANSCEND(2048),
  DARK_EXTREME(8192),
  PHOTON(32768),
  DAYDREAM(131072),
  INFINITE(Integer.MAX_VALUE);

  private final int defaultMaximum;
  private final boolean configurable;

  ParallelControllerTier(int defaultMaximum) {
    this(defaultMaximum, true);
  }

  ParallelControllerTier(int defaultMaximum, boolean configurable) {
    this.defaultMaximum = defaultMaximum;
    this.configurable = configurable;
  }

  public int defaultMaximum() {
    return defaultMaximum;
  }

  public int maximum() {
    return configurable ? MachineConfig.PARALLEL_CONTROLLER_MAXIMA.get(this).get() : defaultMaximum;
  }

  public boolean configurable() {
    return configurable;
  }

  public String path() {
    return name().toLowerCase(Locale.ROOT);
  }
}
