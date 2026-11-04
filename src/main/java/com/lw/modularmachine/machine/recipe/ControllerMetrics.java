package com.lw.modularmachine.machine.recipe;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-wide, monotonic averages for the controller status panels. Values are
 * intentionally cheap: the tick and recipe-search paths only add one atomic
 * update each, and the panels read a snapshot when a status payload is built.
 */
public final class ControllerMetrics {
  private static final long SMOOTHING = 16L;
  private static final AtomicLong TICK_MICROS = new AtomicLong();
  private static final AtomicLong SEARCH_MICROS = new AtomicLong();

  private ControllerMetrics() {
  }

  public static void recordTick(long nanos) {
    update(TICK_MICROS, nanos);
  }

  public static void recordSearch(long nanos) {
    update(SEARCH_MICROS, nanos);
  }

  public static int averageTickMicros() {
    return clamp(TICK_MICROS.get());
  }

  public static int averageSearchMicros() {
    return clamp(SEARCH_MICROS.get());
  }

  private static void update(AtomicLong target, long nanos) {
    long micros = Math.max(0L, nanos / 1_000L);
    target.updateAndGet(current -> current == 0L ? micros : (current * (SMOOTHING - 1L) + micros) / SMOOTHING);
  }

  private static int clamp(long value) {
    return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, value));
  }
}
