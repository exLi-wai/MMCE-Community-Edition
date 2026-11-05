package com.lw.modularmachine.machine.recipe;

/**
 * Server-wide controller timings. Each metric collects samples for one normal
 * 40-tick window (about two seconds at 20 TPS) and publishes the arithmetic
 * mean when that window closes. The published value is held until the next
 * completed window, so menu, Jade, and observer payloads see the same result.
 */
public final class ControllerMetrics {
  public static final long SAMPLE_WINDOW_TICKS = 40L;
  private static final Window TICK = new Window();
  private static final Window SEARCH = new Window();

  private ControllerMetrics() {
  }

  public static void recordTick(long nanos, long gameTick) {
    TICK.record(nanos, gameTick);
  }

  public static void recordSearch(long nanos, long gameTick) {
    SEARCH.record(nanos, gameTick);
  }

  public static int averageTickMicros() {
    return TICK.averageMicros();
  }

  public static int averageSearchMicros() {
    return SEARCH.averageMicros();
  }

  private static final class Window {
    private long startTick = Long.MIN_VALUE;
    private long totalMicros;
    private long samples;
    private int averageMicros;

    synchronized void record(long nanos, long gameTick) {
      long micros = Math.max(0L, nanos / 1_000L);
      if (startTick == Long.MIN_VALUE) {
        startTick = gameTick;
      } else if (gameTick < startTick || gameTick - startTick >= SAMPLE_WINDOW_TICKS) {
        publish();
        startTick = gameTick;
        totalMicros = 0L;
        samples = 0L;
      }
      totalMicros = totalMicros > Long.MAX_VALUE - micros ? Long.MAX_VALUE : totalMicros + micros;
      samples = samples == Long.MAX_VALUE ? Long.MAX_VALUE : samples + 1L;
    }

    synchronized int averageMicros() {
      return averageMicros;
    }

    private void publish() {
      if (samples > 0L) {
        averageMicros = clamp(totalMicros / samples);
      }
    }
  }

  private static int clamp(long value) {
    return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, value));
  }
}
