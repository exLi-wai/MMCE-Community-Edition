package com.lw.modularmachine.machine.recipe;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FrozenRecipeRunSpeedTest {
  @Test
  void boundedMultiStepAdvanceKeepsFrozenDuration() {
    var run = FrozenRecipeRun.read(FrozenRecipeRunTest.valid()).advanced(10, 3);
    assertEquals(3, run.progress());
    assertEquals(FrozenRecipeRunTest.valid().getInt("duration"), run.duration());
  }
}
