package com.lw.modularmachine.machine.recipe;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** Per-controller server timings for the last completed 40-tick window. */
public final class ControllerMetrics {
  public static final long SAMPLE_WINDOW_TICKS = 40L;
  private final Window tick = new Window();
  private final Window search = new Window();

  public void recordTick(long nanos, long gameTick) {
    tick.record(nanos, gameTick);
  }

  public void recordSearch(long nanos, long gameTick) {
    search.record(nanos, gameTick);
  }

  public record Snapshot(int tickMicros, int searchMicros) {
  }

  public Snapshot snapshot(Level level) {
    return level instanceof ServerLevel server ? snapshot(server.getServer().getTickCount()) : new Snapshot(0, 0);
  }

  Snapshot snapshot(long gameTick) {
    return new Snapshot(tick.averageMicros(gameTick), search.averageMicros(gameTick));
  }

  private static final class Window {
    private long window = Long.MIN_VALUE;
    private long totalMicros;
    private long samples;
    private int averageMicros;

    void record(long nanos, long gameTick) {
      advance(gameTick);
      long micros = Math.max(0L, nanos / 1_000L);
      totalMicros = totalMicros > Long.MAX_VALUE - micros ? Long.MAX_VALUE : totalMicros + micros;
      samples = samples == Long.MAX_VALUE ? Long.MAX_VALUE : samples + 1L;
    }

    int averageMicros(long gameTick) {
      advance(gameTick);
      return averageMicros;
    }

    private void advance(long gameTick) {
      long next = Math.floorDiv(gameTick, SAMPLE_WINDOW_TICKS);
      if (next != window) {
        averageMicros = next == window + 1 && samples > 0L ? clamp(totalMicros / samples) : 0;
        window = next;
        samples = 0L;
        totalMicros = 0L;
      }
    }
  }

  private static int clamp(long value) {
    return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, value));
  }
}
