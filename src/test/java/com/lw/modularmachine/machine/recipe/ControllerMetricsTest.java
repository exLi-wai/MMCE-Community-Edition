package com.lw.modularmachine.machine.recipe;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ControllerMetricsTest {
  @Test
  void reportsOnlyThisControllersCompletedWindow() {
    var active = new ControllerMetrics();
    var idle = new ControllerMetrics();
    active.recordSearch(120_000, 10);
    active.recordSearch(180_000, 39);
    active.recordTick(20_000, 40);

    assertEquals(new ControllerMetrics.Snapshot(0, 7), active.snapshot(41));
    assertEquals(new ControllerMetrics.Snapshot(0, 0), idle.snapshot(41));
    assertEquals(new ControllerMetrics.Snapshot(20, 0), active.snapshot(80));
  }

  @Test
  void aLongIdleGapDiscardsOldSamplesBeforeRecordingAgain() {
    var metrics = new ControllerMetrics();
    metrics.recordSearch(500_000, 3);
    assertEquals(0, metrics.snapshot(100).searchMicros());
    metrics.recordSearch(1_160_000, 110);
    assertEquals(29, metrics.snapshot(120).searchMicros());
    assertEquals(0, metrics.snapshot(400).searchMicros());
  }

  @Test
  void combinesSameTickSearchesBeforeConvertingToMicros() {
    var metrics = new ControllerMetrics();
    for (int visit = 0; visit < 80; visit++) {
      metrics.recordSearch(500, 39);
    }
    assertEquals(0, metrics.snapshot(39).searchMicros());
    metrics.recordSearch(2_000_000, 40);
    assertEquals(1, metrics.snapshot(40).searchMicros());
    assertEquals(50, metrics.snapshot(80).searchMicros());
    assertEquals(0, metrics.snapshot(120).searchMicros());
  }
}
