package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParallelAdmissionSearchTest {
  private static final class Probe implements RecipeResourcePlanning {
    private int remaining = 3;
    private final Status result;

    Probe(boolean accepted) {
      result = accepted ? Status.READY : Status.REJECTED;
    }

    @Override
    public Status status() {
      return remaining == 0 ? result : Status.PENDING;
    }

    @Override
    public Progress advance(int budget) {
      int used = Math.min(budget, remaining);
      remaining -= used;
      return new Progress(status(), used);
    }
  }

  @Test
  void scarceExtensionReducesCountAndChecksOneInsteadOfAssumingIt() {
    for (int available : List.of(0, 1, 7, 16)) {
      var requested = new ArrayList<Integer>();
      var search = new ParallelAdmissionSearch(16, count -> {
        requested.add(count);
        return new Probe(count <= available);
      });
      assertEquals(available, search.advance(100));
      assertEquals(100 - requested.size() * 4, search.remainingBudget());
      assertTrue(requested.stream().allMatch(count -> count > 0 && count <= 16));
      if (available <= 1) {
        assertTrue(requested.contains(1));
      }
    }
  }

  @Test
  void slicedSearchRetainsEachProbeAndAccountsForPendingWork() {
    var requested = new ArrayList<Integer>();
    var search = new ParallelAdmissionSearch(16, count -> {
      requested.add(count);
      return new Probe(count <= 1);
    });
    assertEquals(-1, search.advance(0));
    assertTrue(requested.isEmpty());
    int result = -1;
    int calls = 0;
    while (result < 0 && calls++ < 100) {
      result = search.advance(1);
      assertEquals(0, search.remainingBudget());
    }
    assertEquals(1, result);
    assertEquals(List.of(8, 4, 2, 1), requested);
    assertEquals(requested.size() * 4, calls);
    assertEquals(1, search.advance(20));
    assertEquals(20, search.remainingBudget());
  }
}
