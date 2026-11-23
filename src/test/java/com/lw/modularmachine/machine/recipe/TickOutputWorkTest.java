package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.recipe.search.RecipeCandidate;
import com.lw.modularmachine.machine.recipe.search.RecipePlanner;
import com.lw.modularmachine.machine.recipe.search.ResourceSnapshot;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TickOutputWorkTest {
  private static class ItemPlan implements TickOutputWork.Plan {
    private final RecipePlanner.Session<String> session = RecipePlanner.prepareOutputs(
        new ResourceSnapshot<>(
            1,
            Map.of(),
            Collections.nCopies(160, new ResourceSnapshot.OutputSlot<>("dust", 63, 64))),
        RecipeCandidate.outputOnly("test", List.of(new RecipeCandidate.Output<>("dust", 80))));
    private RecipePlanner.Status state = RecipePlanner.Status.BUDGET_EXHAUSTED;
    int work;

    @Override
    public int advance(int budget) {
      var result = session.advance(budget);
      state = result.status();
      work += result.workUsed();
      return result.workUsed();
    }

    @Override
    public boolean pending() {
      return state == RecipePlanner.Status.BUDGET_EXHAUSTED;
    }
  }

  private static final class FluidPlan extends ItemPlan {
  }

  private static final class ChemicalPlan extends ItemPlan {
  }

  @Test
  void competingBatchesReachDependentOutputsWithinOneOwnerBudget() {
    var work = new TickOutputWork();
    var first = UUID.randomUUID();
    var second = UUID.randomUUID();
    var a = new ItemPlan();
    var b = new ItemPlan();
    var fluid = new FluidPlan();
    var chemical = new ChemicalPlan();
    for (int tick = 0; tick < 80; tick++) {
      int before = a.work + b.work + fluid.work + chemical.work;
      assertSame(a, work.visit(tick, ItemPlan.class, first, plan -> plan == a, () -> a));
      assertSame(b, work.visit(tick, ItemPlan.class, second, plan -> plan == b, () -> b));
      if (!a.pending()) {
        work.visit(tick, FluidPlan.class, first, plan -> true, () -> fluid);
        if (!fluid.pending()) {
          work.visit(tick, ChemicalPlan.class, first, plan -> true, () -> chemical);
        }
      }
      assertTrue(a.work + b.work + fluid.work + chemical.work - before <= 64);
      if (tick == 2) {
        assertTrue(a.work > 0 && b.work > 0);
      }
    }
    for (var plan : List.of(a, b, fluid, chemical)) {
      assertEquals(RecipePlanner.Status.SELECTED, plan.state);
    }
  }

  @Test
  void replacementKeepsSpentBudgetAndRemovedRunsLoseTheirPlans() {
    var work = new TickOutputWork();
    var batch = UUID.randomUUID();
    var initial = work.visit(8, ItemPlan.class, batch, plan -> true, ItemPlan::new);
    assertEquals(64, initial.work);
    var replacement = work.visit(8, ItemPlan.class, batch, plan -> false, ItemPlan::new);
    assertNotSame(initial, replacement);
    assertEquals(0, replacement.work);
    assertSame(replacement, work.visit(9, ItemPlan.class, batch, plan -> true, ItemPlan::new));
    assertEquals(64, replacement.work);
    work.begin(10);
    work.retainPaidRuns(11, Stream::empty);
    var resumed = work.visit(11, ItemPlan.class, batch, plan -> true, ItemPlan::new);
    assertNotSame(replacement, resumed);
    assertEquals(64, resumed.work);
  }

  @Test
  void paidRunCompletesLargeOutputAcrossAlternatingTickPermission() {
    var work = new TickOutputWork();
    var run = UUID.randomUUID();
    var initial = work.visit(0, ItemPlan.class, run, unused -> true, ItemPlan::new);
    assertTrue(initial.pending());
    for (int tick = 1; tick <= 80 && initial.pending(); tick++) {
      work.retainPaidRuns(tick, () -> Stream.of(run));
      work.begin(tick);
      if (tick % 2 == 0) {
        assertSame(initial, work.visit(tick, ItemPlan.class, run, plan -> true, ItemPlan::new));
      }
    }
    assertEquals(RecipePlanner.Status.SELECTED, initial.state);
    work.retainPaidRuns(100, () -> Stream.of(run));
    var changed = work.visit(100, ItemPlan.class, run, plan -> false, ItemPlan::new);
    assertNotSame(initial, changed);
    assertEquals(64, changed.work);
  }
}
