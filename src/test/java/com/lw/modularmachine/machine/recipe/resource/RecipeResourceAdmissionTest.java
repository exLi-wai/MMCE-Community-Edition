package com.lw.modularmachine.machine.recipe.resource;

import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecipeResourceAdmissionTest {
  private static final PreparedRecipeResource UNACQUIRED = new PreparedRecipeResource() {
    @Override
    public void acquire() {
      fail("Admission cannot acquire a resource");
    }

    @Override
    public void resolve(boolean commit) {
      fail("Admission cannot resolve an unacquired resource");
    }
  };

  private static RecipeResourcePlanning output(int work) {
    return new RecipeResourcePlanning() {
      private int remaining = work;

      @Override
      public Status status() {
        return remaining == 0 ? Status.READY : Status.PENDING;
      }

      @Override
      public Progress advance(int budget) {
        int used = Math.min(remaining, budget);
        remaining -= used;
        return new Progress(status(), used);
      }
    };
  }

  @Test
  void inputAndOutputPreparationAreDeferredAndRetainedAcrossSlices() {
    var inputs = new AtomicInteger();
    var outputs = new AtomicInteger();
    var planning = new RecipeResourceExecutionRegistry.AdmissionPlanning(() -> {
      inputs.incrementAndGet();
      return UNACQUIRED;
    }, () -> {
      outputs.incrementAndGet();
      return output(3);
    });
    assertEquals(new RecipeResourcePlanning.Progress(RecipeResourcePlanning.Status.PENDING, 0), planning.advance(0));
    assertEquals(0, inputs.get());
    assertEquals(0, outputs.get());
    assertEquals(new RecipeResourcePlanning.Progress(RecipeResourcePlanning.Status.PENDING, 1), planning.advance(1));
    assertEquals(1, inputs.get());
    assertEquals(0, outputs.get());
    assertEquals(new RecipeResourcePlanning.Progress(RecipeResourcePlanning.Status.PENDING, 2), planning.advance(2));
    assertEquals(1, outputs.get());
    assertEquals(new RecipeResourcePlanning.Progress(RecipeResourcePlanning.Status.READY, 2), planning.advance(2));
    assertEquals(1, inputs.get());
    assertEquals(1, outputs.get());
    assertEquals(0, planning.advance(10).workUsed());
  }

  @Test
  void unavailableInputRejectsWithoutPreparingAnOutputOrAcquiringAnything() {
    var planning = new RecipeResourceExecutionRegistry.AdmissionPlanning(() -> null, () -> {
      fail("Rejected input cannot prepare output");
      return output(0);
    });
    assertEquals(new RecipeResourcePlanning.Progress(RecipeResourcePlanning.Status.REJECTED, 1), planning.advance(10));
    assertEquals(0, planning.advance(10).workUsed());
  }

  @Test
  void quantityOverflowRejectsTheProbeButOtherStrategyFailuresPropagate() {
    var inputOverflow = new RecipeResourceExecutionRegistry.AdmissionPlanning(() -> {
      Math.multiplyExact(Long.MAX_VALUE, 2);
      return UNACQUIRED;
    }, () -> output(0));
    assertEquals(RecipeResourcePlanning.Status.REJECTED, inputOverflow.advance(10).status());
    var outputOverflow = new RecipeResourceExecutionRegistry.AdmissionPlanning(() -> UNACQUIRED, () -> {
      Math.multiplyExact(Long.MAX_VALUE, 2);
      return output(0);
    });
    assertEquals(RecipeResourcePlanning.Status.REJECTED, outputOverflow.advance(10).status());
    var invalid = new RecipeResourceExecutionRegistry.AdmissionPlanning(() -> {
      throw new IllegalStateException("Broken resource implementation");
    }, () -> output(0));
    assertThrows(IllegalStateException.class, () -> invalid.advance(10));
  }
}
