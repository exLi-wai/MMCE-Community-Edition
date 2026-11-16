package com.lw.modularmachine.machine.recipe.resource;

import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.recipe.search.RecipePlanner;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecipeResourceOutputPlanningTest {
  private static RecipeResourceExecution.Output output(int work, RecipeResourcePlanning.Status terminal) {
    return new RecipeResourceExecution.Output() {
      private int remaining = work;

      @Override
      public Status status() {
        return remaining == 0 ? terminal : Status.PENDING;
      }

      @Override
      public Progress advance(int budget) {
        int used = Math.min(remaining, budget);
        remaining -= used;
        return new Progress(status(), used);
      }

      @Override
      public PreparedRecipeResource prepared(RecipeExecutionTicket checked) {
        fail("Read-only planning cannot prepare the commit participant");
        return null;
      }
    };
  }

  @Test
  void registryOutputFactoriesWaitForBudgetAndPreviousResourceCompletion() {
    var first = new AtomicInteger();
    var second = new AtomicInteger();
    // No world proof is needed for pure planning. prepared() is deliberately never
    // called.
    var planning = new RecipeResourceExecutionRegistry.OutputPlanning(null, List.of(() -> {
      first.incrementAndGet();
      return output(2, RecipeResourcePlanning.Status.READY);
    }, () -> {
      second.incrementAndGet();
      return output(1, RecipeResourcePlanning.Status.READY);
    }));
    assertEquals(
        new RecipeResourceExecutionRegistry.OutputPlanning.Progress(RecipePlanner.Status.BUDGET_EXHAUSTED, 0),
        planning.advance(0));
    assertEquals(0, first.get());
    assertEquals(0, second.get());
    assertEquals(RecipePlanner.Status.BUDGET_EXHAUSTED, planning.advance(1).status());
    assertEquals(RecipePlanner.Status.BUDGET_EXHAUSTED, planning.advance(1).status());
    assertEquals(1, first.get());
    assertEquals(0, second.get());
    assertEquals(RecipePlanner.Status.SELECTED, planning.advance(1).status());
    assertEquals(1, first.get());
    assertEquals(1, second.get());
  }

  @Test
  void rejectedOutputDoesNotConstructFollowingFinishStrategy() {
    var attempts = new AtomicInteger();
    var planning = new RecipeResourceExecutionRegistry.OutputPlanning(null, List.of(() -> {
      attempts.incrementAndGet();
      return output(1, RecipeResourcePlanning.Status.REJECTED);
    }, () -> {
      fail("Rejected output cannot construct a later finish plan");
      return output(1, RecipeResourcePlanning.Status.READY);
    }));
    assertEquals(
        new RecipeResourceExecutionRegistry.OutputPlanning.Progress(RecipePlanner.Status.NO_MATCH, 1),
        planning.advance(10));
    assertEquals(
        new RecipeResourceExecutionRegistry.OutputPlanning.Progress(RecipePlanner.Status.NO_MATCH, 2),
        planning.advance(1000));
    assertEquals(2, attempts.get(), "A blocked strategy retries at most once per owner attempt");
  }

  @Test
  void privateStorageRecoveryReplansWithoutNativeTicketInvalidation() {
    var full = new AtomicBoolean(true);
    var captures = new AtomicInteger();
    var planning = new RecipeResourceExecutionRegistry.OutputPlanning(null, List.of(() -> {
      captures.incrementAndGet();
      // Capture storage availability only when Finish.prepare is invoked.
      return output(2, full.get() ? RecipeResourcePlanning.Status.REJECTED : RecipeResourcePlanning.Status.READY);
    }));
    assertEquals(RecipePlanner.Status.NO_MATCH, planning.advance(2).status());
    full.set(false);
    assertThrows(IllegalArgumentException.class, () -> planning.advance(-1));
    assertEquals(
        new RecipeResourceExecutionRegistry.OutputPlanning.Progress(RecipePlanner.Status.NO_MATCH, 0),
        planning.advance(0));
    assertEquals(1, captures.get());
    assertEquals(
        new RecipeResourceExecutionRegistry.OutputPlanning.Progress(RecipePlanner.Status.SELECTED, 3),
        planning.advance(3));
    assertEquals(2, captures.get());
    assertEquals(0, planning.advance(10).workUsed());
    assertEquals(2, captures.get(), "Ready output plans are retained for commit validation");
  }

  @Test
  void retryRecapturesAllExtensionsAndKeepsProgressAcrossSmallBudgets() {
    var first = new AtomicInteger();
    var second = new AtomicInteger();
    var planning = new RecipeResourceExecutionRegistry.OutputPlanning(null, List.of(() -> {
      first.incrementAndGet();
      return output(2, RecipeResourcePlanning.Status.READY);
    },
        () -> output(
            2,
            second
                .incrementAndGet() == 1 ? RecipeResourcePlanning.Status.REJECTED : RecipeResourcePlanning.Status.READY)));
    assertEquals(RecipePlanner.Status.NO_MATCH, planning.advance(4).status());
    assertEquals(
        new RecipeResourceExecutionRegistry.OutputPlanning.Progress(RecipePlanner.Status.BUDGET_EXHAUSTED, 1),
        planning.advance(1));
    assertEquals(1, first.get(), "Reset consumes the slice before any new Finish.prepare call");
    assertEquals(1, second.get());
    assertEquals(0, planning.advance(0).workUsed());
    for (int i = 0; i < 3; i++) {
      assertEquals(
          new RecipeResourceExecutionRegistry.OutputPlanning.Progress(RecipePlanner.Status.BUDGET_EXHAUSTED, 1),
          planning.advance(1));
    }
    assertEquals(
        new RecipeResourceExecutionRegistry.OutputPlanning.Progress(RecipePlanner.Status.SELECTED, 1),
        planning.advance(1));
    assertEquals(2, first.get());
    assertEquals(2, second.get());
  }
}
