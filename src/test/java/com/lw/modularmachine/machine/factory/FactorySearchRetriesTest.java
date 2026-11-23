package com.lw.modularmachine.machine.factory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class FactorySearchRetriesTest {
  @Test
  void completedMissesBackOffUntilAResourceChangeAndCanWakeTwiceInOneTick() {
    var retries = new FactorySearchCoordinator.Retries();
    long now = 0;
    for (int delay : new int[]{5, 10, 20, 40, 80, 80}) {
      retries.defer("main", now);
      assertTrue(retries.waiting("main", now + delay - 1));
      now += delay;
      assertFalse(retries.waiting("main", now));
    }
    retries.defer("main", now);
    retries.defer("extra", now);
    retries.reset("extra");
    assertTrue(retries.waiting("main", now));
    retries.wake();
    retries.defer("main", now);
    assertFalse(retries.waiting("main", now + 5));
    retries.wake();
    assertFalse(retries.waiting("main", now));
  }
}
