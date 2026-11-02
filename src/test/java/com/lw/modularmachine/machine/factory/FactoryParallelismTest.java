package com.lw.modularmachine.machine.factory;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FactoryParallelismTest {
  @Test
  void effectiveInstancesUsesEveryBound() {
    assertEquals(3, FactoryParallelism.effectiveInstances(8, 3, 9, 7));
    assertEquals(2, FactoryParallelism.effectiveInstances(8, 9, 2, 7));
    assertEquals(4, FactoryParallelism.effectiveInstances(8, 9, 9, 4));
    assertEquals(0, FactoryParallelism.effectiveInstances(8, 0, 9, 7));
  }

  @Test
  void intMaximumLimitIsReducedOnlyByAvailableCapacityAndBudget() {
    assertEquals(
        Integer.MAX_VALUE,
        FactoryParallelism
            .effectiveInstances(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
    assertEquals(1200, FactoryParallelism.effectiveInstances(Integer.MAX_VALUE, 1200, 2400, Integer.MAX_VALUE));
  }

  @Test
  void rejectsNegativeAdmissionInputs() {
    assertThrows(IllegalArgumentException.class, () -> FactoryParallelism.effectiveInstances(0, 1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> FactoryParallelism.effectiveInstances(1, -1, 1, 1));
  }
}
