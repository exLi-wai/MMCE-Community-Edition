package com.lw.modularmachine.machine.recipe;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** Per-controller server timings for the last completed 40-tick window. */
public final class ControllerMetrics {
  public static final long SAMPLE_WINDOW_TICKS = 40L;
  private long window = Long.MIN_VALUE;
  private long tickMicros;
  private long tickSamples;
  private long searchNanos;
  private int averageTickMicros;
  private int averageSearchMicros;

  public void recordTick(long nanos, long gameTick) {
    advance(gameTick);
    tickMicros = addDuration(tickMicros, nanos / 1_000L);
    if (tickSamples < Long.MAX_VALUE) {
      tickSamples++;
    }
  }

  public void recordSearch(long nanos, long gameTick) {
    advance(gameTick);
    searchNanos = addDuration(searchNanos, nanos);
  }

  public record Snapshot(int tickMicros, int searchMicros) {
  }

  public Snapshot snapshot(Level level) {
    return level instanceof ServerLevel server ? snapshot(server.getServer().getTickCount()) : new Snapshot(0, 0);
  }

  Snapshot snapshot(long gameTick) {
    advance(gameTick);
    return new Snapshot(averageTickMicros, averageSearchMicros);
  }

  private void advance(long gameTick) {
    long next = Math.floorDiv(gameTick, SAMPLE_WINDOW_TICKS);
    if (next == window) {
      return;
    }
    boolean previousWindow = next == window + 1;
    averageTickMicros = previousWindow && tickSamples > 0 ? clamp(tickMicros / tickSamples) : 0;
    // Searches on all factory threads share these server ticks, including idle
    // ticks.
    averageSearchMicros = previousWindow ? clamp(searchNanos / SAMPLE_WINDOW_TICKS / 1_000L) : 0;
    window = next;
    tickMicros = 0;
    tickSamples = 0;
    searchNanos = 0;
  }

  private static long addDuration(long total, long elapsed) {
    long duration = Math.max(0L, elapsed);
    return total > Long.MAX_VALUE - duration ? Long.MAX_VALUE : total + duration;
  }

  private static int clamp(long value) {
    return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, value));
  }
}
