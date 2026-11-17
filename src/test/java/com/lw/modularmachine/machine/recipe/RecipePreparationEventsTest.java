package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.recipe.RecipePreparationEvents;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecipePreparationEventsTest {
  @Test
  void checkCanOnlyLowerLimitAndCannotUndoEarlierFailure() {
    var event = new RecipePreparationEvents.Event(null, RecipePreparationEvents.Type.PRE_CHECK, 8);
    event.setParallelism(3);
    event.setParallelism(7);
    event.setFailed("disabled");
    event.setCanceled(false);
    assertEquals(3, event.getParallelism());
    assertTrue(event.isCanceled());
    event.seal();
    assertThrows(IllegalStateException.class, () -> event.setParallelism(1));
  }

  @Test
  void preTickPausesWithoutGrantingRunDestructionOrRescaling() {
    var event = new RecipePreparationEvents.Event(null, RecipePreparationEvents.Type.PRE_TICK, 4);
    assertThrows(IllegalArgumentException.class, () -> event.setFailed(true, "destroy"));
    assertThrows(IllegalArgumentException.class, () -> event.setParallelism(1));
    event.preventProgressing("paused");
    assertTrue(event.isCanceled());
    assertEquals("paused", event.getFailureReason());
    assertEquals("START", event.getPhase());
  }
}
