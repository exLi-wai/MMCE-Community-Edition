package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResourcePlanningSequenceTest {
  private static final class Step implements RecipeResourcePlanning {
    private int remaining;
    private final Status terminal;
    private boolean waiting;

    Step(int remaining, Status terminal) {
      this.remaining = remaining;
      this.terminal = terminal;
    }

    @Override
    public Status status() {
      return remaining == 0 ? terminal : Status.PENDING;
    }

    @Override
    public Progress advance(int budget) {
      int used = waiting ? 0 : Math.min(remaining, budget);
      remaining -= used;
      return new Progress(status(), used);
    }
  }

  private static ResourcePlanningSequence threeResources() {
    return new ResourcePlanningSequence(
        List.of(
            () -> new Step(3, RecipeResourcePlanning.Status.READY),
            () -> new Step(2, RecipeResourcePlanning.Status.READY),
            () -> new Step(4, RecipeResourcePlanning.Status.READY)));
  }

  @Test
  void slicedPlanningMatchesOneShotAndSharesTheWholeBudget() {
    var whole = threeResources().advance(20);
    var sliced = threeResources();
    int total = 0;
    for (int call = 0; call < 9; call++) {
      var progress = sliced.advance(1);
      assertEquals(1, progress.workUsed());
      total += progress.workUsed();
      assertEquals(
          call == 8 ? RecipeResourcePlanning.Status.READY : RecipeResourcePlanning.Status.PENDING,
          progress.status());
    }
    assertEquals(whole.workUsed(), total);
    assertEquals(whole.status(), sliced.status());
    assertEquals(0, sliced.advance(10).workUsed());
    var mixed = threeResources();
    assertEquals(4, mixed.advance(4).workUsed());
    assertEquals(5, mixed.advance(20).workUsed());
    assertEquals(RecipeResourcePlanning.Status.READY, mixed.status());
  }

  @Test
  void zeroBudgetCannotConstructOrAdvanceAResourcePlan() {
    var created = new AtomicInteger();
    var sequence = new ResourcePlanningSequence(List.of(() -> {
      created.incrementAndGet();
      return new Step(1, RecipeResourcePlanning.Status.READY);
    }));
    assertEquals(new RecipeResourcePlanning.Progress(RecipeResourcePlanning.Status.PENDING, 0), sequence.advance(0));
    assertEquals(0, created.get());
    assertThrows(IllegalArgumentException.class, () -> sequence.advance(-1));
    assertEquals(RecipeResourcePlanning.Status.READY, sequence.advance(1).status());
    assertEquals(1, created.get());
  }

  @Test
  void rejectedResourceDoesNotPrepareLaterResourcesOrRestart() {
    var created = new AtomicInteger();
    var sequence = new ResourcePlanningSequence(
        List.of(() -> new Step(2, RecipeResourcePlanning.Status.REJECTED), () -> {
          created.incrementAndGet();
          return new Step(1, RecipeResourcePlanning.Status.READY);
        }));
    assertEquals(new RecipeResourcePlanning.Progress(RecipeResourcePlanning.Status.REJECTED, 2), sequence.advance(20));
    assertEquals(0, sequence.advance(20).workUsed());
    assertEquals(0, created.get());
  }

  @Test
  void temporarilyIdleStepYieldsWithoutRecreatingItsState() {
    var step = new Step(2, RecipeResourcePlanning.Status.READY);
    step.waiting = true;
    var created = new AtomicInteger();
    var sequence = new ResourcePlanningSequence(List.of(() -> {
      created.incrementAndGet();
      return step;
    }));
    assertEquals(new RecipeResourcePlanning.Progress(RecipeResourcePlanning.Status.PENDING, 0), sequence.advance(20));
    step.waiting = false;
    assertEquals(new RecipeResourcePlanning.Progress(RecipeResourcePlanning.Status.READY, 2), sequence.advance(20));
    assertEquals(1, created.get());
  }

  @Test
  void rejectsOverBudgetOrInconsistentProgressBeforeAnotherStep() {
    for (boolean overBudget : List.of(true, false)) {
      var sequence = new ResourcePlanningSequence(List.of(() -> new RecipeResourcePlanning() {
        @Override
        public Status status() {
          return Status.PENDING;
        }

        @Override
        public Progress advance(int budget) {
          return overBudget ? new Progress(Status.PENDING, budget + 1) : new Progress(Status.READY, 0);
        }
      }));
      assertThrows(IllegalStateException.class, () -> sequence.advance(1));
    }
  }

  @Test
  void emptySequenceIsReadyWithoutSpendingWork() {
    var sequence = new ResourcePlanningSequence(List.of());
    assertEquals(RecipeResourcePlanning.Status.READY, sequence.status());
    assertEquals(0, sequence.advance(0).workUsed());
    assertEquals(0, sequence.advance(100).workUsed());
  }
}
