package com.lw.modularmachine.machine;

import com.lw.modularmachine.config.MachineConfig;
import java.util.Locale;

public enum ParallelControllerTier {
  NORMAL(4),
  REINFORCED(16),
  ELITE(64),
  SUPER(256),
  ULTIMATE(512);

  private final int defaultMaximum;

  ParallelControllerTier(int defaultMaximum) {
    this.defaultMaximum = defaultMaximum;
  }

  public int defaultMaximum() {
    return defaultMaximum;
  }

  public int maximum() {
    return MachineConfig.PARALLEL_CONTROLLER_MAXIMA.get(this).get();
  }

  public String path() {
    return name().toLowerCase(Locale.ROOT);
  }
}
