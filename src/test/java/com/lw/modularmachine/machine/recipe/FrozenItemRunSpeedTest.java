package com.lw.modularmachine.machine.recipe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FrozenItemRunSpeedTest {
  @Test
  void boundedMultiStepAdvanceKeepsFrozenDuration() {
    var run = FrozenItemRun.read(FrozenItemRunTest.valid()).advanced(10, 3);
    assertEquals(3, run.progress());
    assertEquals(FrozenItemRunTest.valid().getInt("duration"), run.duration());
  }
}
