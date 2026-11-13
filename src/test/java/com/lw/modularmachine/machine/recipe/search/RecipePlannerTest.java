package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.machine.recipe.RecipeStartPlan;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecipePlannerTest {
  private static RecipeCandidate<String> recipe(String id, int priority, String input, long amount, String output) {
    return new RecipeCandidate<String>(
        id,
        priority,
        List.of(new RecipeCandidate.Input<String>(input, amount)),
        List.of(new RecipeCandidate.Output<String>(output, 1)));
  }

  @Test
  void inputChanceZeroIsRequiredButNotSampledForDeduction() {
    var candidate = new RecipeCandidate<String>(
        "catalyst",
        0,
        List.of(
            new RecipeCandidate.Input<>("minecraft:bucket", 1, 0),
            new RecipeCandidate.Input<>("minecraft:diamond", 2, 1)),
        List.of(new RecipeCandidate.Output<>("minecraft:emerald", 1)));
    var sampled = RecipeStartPlan.sampledInputs(candidate, 42, 1);

    assertFalse(sampled.containsKey("minecraft:bucket"));
    assertEquals(2L, sampled.get("minecraft:diamond"));
  }

  @Test
  void inputChanceUsesOneDeterministicRollPerParallelInstance() {
    var candidate = new RecipeCandidate<String>(
        "probabilistic",
        0,
        List.of(new RecipeCandidate.Input<>("minecraft:diamond", 1, .5D)),
        List.of(new RecipeCandidate.Output<>("minecraft:emerald", 1)));
    assertEquals(RecipeStartPlan.sampledInputs(candidate, 81, 12), RecipeStartPlan.sampledInputs(candidate, 81, 12));
    assertTrue(RecipeStartPlan.sampledInputs(candidate, 81, 12).getOrDefault("minecraft:diamond", 0L) <= 12);
  }

  @Test
  void choosesHighestPriorityCompleteCandidate() {
    var snapshot = new ResourceSnapshot<String>(7, Map.of("minecraft:dirt", 2L), List.of(empty(0)));
    var selected = RecipePlanner.select(
        snapshot,
        List.of(recipe("low", 1, "minecraft:dirt", 1, "mmcece:b"), recipe("high", 5, "minecraft:dirt", 1, "mmcece:a")))
        .orElseThrow();
    assertEquals("high", selected.candidate().id());
    assertEquals(7, selected.sourceRevision());
  }

  @Test
  void aggregatesDuplicateInputsInsteadOfCheckingEachAgainstOriginalCount() {
    var candidate = new RecipeCandidate<String>(
        "duplicate",
        1,
        List.of(
            new RecipeCandidate.Input<String>("minecraft:dirt", 2),
            new RecipeCandidate.Input<String>("minecraft:dirt", 2)),
        List.of(new RecipeCandidate.Output<String>("mmcece:brick", 1)));
    var snapshot = new ResourceSnapshot<String>(0, Map.of("minecraft:dirt", 3L), List.of(empty(0)));
    assertTrue(RecipePlanner.select(snapshot, List.of(candidate)).isEmpty());
  }

  @Test
  void outputBlockedHighPriorityFallsThroughToLowerPriorityCandidate() {
    var snapshot = new ResourceSnapshot<String>(
        0,
        Map.of("minecraft:dirt", 1L),
        List.of(new ResourceSnapshot.OutputSlot<String>("mmcece:b", 63, 64)));
    var selected = RecipePlanner
        .select(
            snapshot,
            List.of(recipe("a", 10, "minecraft:dirt", 1, "mmcece:a"), recipe("b", 1, "minecraft:dirt", 1, "mmcece:b")))
        .orElseThrow();
    assertEquals("b", selected.candidate().id());
    assertEquals(1, selected.insertions().getFirst().count());
  }

  @Test
  void failedCandidateDoesNotLeaveCapacityReservationForNextCandidate() {
    var snapshot = new ResourceSnapshot<String>(
        0,
        Map.of("minecraft:dirt", 1L),
        List.of(new ResourceSnapshot.OutputSlot<String>(null, 0, 1)));
    var a = new RecipeCandidate<String>(
        "a",
        10,
        List.of(new RecipeCandidate.Input<String>("minecraft:dirt", 1)),
        List.of(new RecipeCandidate.Output<String>("mmcece:a", 2)));
    var b = recipe("b", 1, "minecraft:dirt", 1, "mmcece:b");
    assertEquals("b", RecipePlanner.select(snapshot, List.of(a, b)).orElseThrow().candidate().id());
  }

  @Test
  void plannerDoesNotMutateSnapshot() {
    var snapshot = new ResourceSnapshot<String>(3, Map.of("minecraft:dirt", 1L), List.of(empty(0)));
    RecipePlanner.select(snapshot, List.of(recipe("a", 1, "minecraft:dirt", 1, "mmcece:a"))).orElseThrow();
    assertEquals(1, snapshot.inputCount("minecraft:dirt"));
    assertEquals(0, snapshot.outputs().getFirst().count());
  }

  @Test
  void samePriorityOrderingIsStableByRecipeId() {
    var snapshot = new ResourceSnapshot<String>(0, Map.of("minecraft:dirt", 1L), List.of(empty(0)));
    var selected = RecipePlanner
        .select(
            snapshot,
            List.of(recipe("z", 2, "minecraft:dirt", 1, "mmcece:z"), recipe("a", 2, "minecraft:dirt", 1, "mmcece:a")))
        .orElseThrow();
    assertEquals("a", selected.candidate().id());
  }

  @Test
  void differentOutputsCannotShareOneEmptySlot() {
    var snapshot = new ResourceSnapshot<String>(0, Map.of("minecraft:dirt", 1L), List.of(empty(0)));
    var candidate = new RecipeCandidate<String>(
        "two_outputs",
        1,
        List.of(new RecipeCandidate.Input<String>("minecraft:dirt", 1)),
        List.of(new RecipeCandidate.Output<String>("mmcece:a", 1), new RecipeCandidate.Output<String>("mmcece:b", 1)));
    assertTrue(RecipePlanner.select(snapshot, List.of(candidate)).isEmpty());
  }

  @Test
  void existingMatchingStackIsPreferredOverWildcardSlot() {
    var snapshot = new ResourceSnapshot<String>(
        0,
        Map.of("minecraft:dirt", 1L),
        List.of(
            new ResourceSnapshot.OutputSlot<String>(null, 0, 1),
            new ResourceSnapshot.OutputSlot<String>("mmcece:b", 63, 64)));
    var candidate = new RecipeCandidate<String>(
        "two_outputs",
        1,
        List.of(new RecipeCandidate.Input<String>("minecraft:dirt", 1)),
        List.of(new RecipeCandidate.Output<String>("mmcece:b", 1), new RecipeCandidate.Output<String>("mmcece:a", 1)));
    var plan = RecipePlanner.select(snapshot, List.of(candidate)).orElseThrow();
    assertEquals(List.of(1, 0), plan.insertions().stream().map(RecipePlan.Insertion::slot).toList());
  }

  @Test
  void overflowingDuplicateRequirementIsRejected() {
    var candidate = new RecipeCandidate<String>(
        "overflow",
        1,
        List.of(
            new RecipeCandidate.Input<String>("minecraft:dirt", Long.MAX_VALUE),
            new RecipeCandidate.Input<String>("minecraft:dirt", 1)),
        List.of(new RecipeCandidate.Output<String>("mmcece:a", 1)));
    var snapshot = new ResourceSnapshot<String>(0, Map.of("minecraft:dirt", Long.MAX_VALUE), List.of(empty(0)));
    assertThrows(IllegalArgumentException.class, () -> RecipePlanner.select(snapshot, List.of(candidate)));
  }

  @Test
  void unequalEmptyCapacitiesRequireExactAssignment() {
    var snapshot = new ResourceSnapshot<String>(
        0,
        Map.of("minecraft:dirt", 1L),
        List.of(
            new ResourceSnapshot.OutputSlot<String>(null, 0, 1),
            new ResourceSnapshot.OutputSlot<String>(null, 0, 64)));
    var candidate = new RecipeCandidate<String>(
        "mixed",
        1,
        List.of(new RecipeCandidate.Input<String>("minecraft:dirt", 1)),
        List.of(new RecipeCandidate.Output<String>("a", 64), new RecipeCandidate.Output<String>("b", 1)));
    var plan = RecipePlanner.select(snapshot, List.of(candidate)).orElseThrow();
    assertEquals(65, plan.insertions().stream().mapToLong(RecipePlan.Insertion::count).sum());
    assertEquals("b", plan.insertions().stream().filter(i -> i.slot() == 0).findFirst().orElseThrow().key());
  }

  @Test
  void budgetExhaustionDoesNotSelectALowerPriorityRecipeOrClaimNoMatch() {
    var snapshot = new ResourceSnapshot<String>(0, Map.of("minecraft:dirt", 1L), List.of(empty(0)));
    var result = RecipePlanner.search(
        snapshot,
        List.of(recipe("a", 10, "minecraft:dirt", 1, "a"), recipe("b", 0, "minecraft:dirt", 1, "b")),
        2);
    assertEquals(RecipePlanner.Status.BUDGET_EXHAUSTED, result.status());
    assertEquals(2, result.workUsed());
    assertTrue(result.plan().isEmpty());
    assertThrows(IllegalArgumentException.class, () -> RecipePlanner.search(snapshot, List.of(), -1));
  }

  @Test
  void rareRequiredInputSkipsAnImpossibleHighPriorityCandidateWithoutChangingFallback() {
    var high = new RecipeCandidate<String>(
        "high",
        10,
        List.of(
            new RecipeCandidate.Input<String>("rare_a", 1),
            new RecipeCandidate.Input<String>("rare_b", 1),
            new RecipeCandidate.Input<String>("rare_c", 1),
            new RecipeCandidate.Input<String>("rare_d", 1)),
        List.of(new RecipeCandidate.Output<String>("high_output", 1)));
    var low = recipe("low", 1, "present", 1, "low_output");
    var snapshot = new ResourceSnapshot<String>(0, Map.of("present", 1L), List.of(empty(0)));
    var compiled = new CompiledRecipeSet<>(List.of(high, low));
    assertEquals(1, compiled.entriesFor(snapshot, false).size());
    var session = RecipePlanner.prepare(snapshot, compiled);

    RecipePlanner.SearchResult<String> result;
    do {
      result = session.advance(1);
      assertTrue(result.workUsed() <= 1);
    } while (result.status() == RecipePlanner.Status.BUDGET_EXHAUSTED);

    assertEquals(RecipePlanner.Status.SELECTED, result.status());
    assertEquals("low", result.plan().orElseThrow().candidate().id());
    assertTrue(session.totalWork() > 0, "The filtered session should still perform the selected candidate work");
  }

  @Test
  void fullOutputAndOutputOnlyChangesReevaluatePriority() {
    var a = recipe("a", 10, "minecraft:dirt", 1, "a");
    var b = recipe("b", 1, "minecraft:dirt", 1, "b");
    assertTrue(
        RecipePlanner.select(
            new ResourceSnapshot<String>(
                0,
                Map.of("minecraft:dirt", 1L),
                List.of(new ResourceSnapshot.OutputSlot<String>("b", 64, 64))),
            List.of(a, b)).isEmpty());
    assertEquals(
        "a",
        RecipePlanner
            .select(new ResourceSnapshot<String>(1, Map.of("minecraft:dirt", 1L), List.of(empty(0))), List.of(a, b))
            .orElseThrow().candidate().id());
  }

  @Test
  void exactAllocationMatchesIndependentSlotTypeEnumeration() {
    var random = new Random(0x2406L);
    for (int example = 0; example < 1000; example++) {
      var slots = new ArrayList<ResourceSnapshot.OutputSlot<String>>();
      for (int i = 0, n = random.nextInt(5); i < n; i++) {
        int type = random.nextInt(4), capacity = 1 + random.nextInt(5);
        slots.add(
            new ResourceSnapshot.OutputSlot<String>(
                type == 3 ? null : "o" + type,
                type == 3 ? 0 : random.nextInt(capacity + 1),
                capacity));
      }
      var outputs = new ArrayList<RecipeCandidate.Output<String>>();
      long[] wanted = new long[3];
      for (int type = 0; type < 3; type++) {
        wanted[type] = 1 + random.nextInt(6);
        outputs.add(new RecipeCandidate.Output<String>("o" + type, wanted[type]));
      }
      var candidate = new RecipeCandidate<String>(
          "test",
          0,
          List.of(new RecipeCandidate.Input<String>("dirt", 1)),
          outputs);
      boolean reference = enumerate(slots, 0, wanted, new long[3]);
      var result = RecipePlanner
          .search(new ResourceSnapshot<String>(0, Map.of("dirt", 1L), slots), List.of(candidate), 100_000);
      assertNotEquals(RecipePlanner.Status.BUDGET_EXHAUSTED, result.status());
      assertEquals(reference, result.plan().isPresent(), "Example " + example + ": " + slots);
      result.plan().ifPresent(plan -> {
        long[] totals = new long[3], slotTotals = new long[slots.size()];
        String[] types = new String[slots.size()];
        for (var insertion : plan.insertions()) {
          int slot = insertion.slot();
          if (types[slot] != null) {
            assertEquals(types[slot], insertion.key());
          }
          if (slots.get(slot).key() != null) {
            assertEquals(slots.get(slot).key(), insertion.key());
          }
          types[slot] = insertion.key();
          totals[Integer.parseInt(insertion.key().substring(1))] += insertion.count();
          slotTotals[slot] += insertion.count();
          assertTrue(slotTotals[slot] <= slots.get(slot).capacity() - slots.get(slot).count());
        }
        assertArrayEquals(wanted, totals);
      });
    }
  }

  // Independent oracle assigns a whole empty slot to a type and compares summed
  // capacity.
  private static boolean enumerate(List<ResourceSnapshot.OutputSlot<String>> slots, int index, long[] wanted, long[] capacity) {
    if (index == slots.size()) {
      for (int i = 0; i < wanted.length; i++) {
        if (capacity[i] < wanted[i]) {
          return false;
        }
      }
      return true;
    }
    var slot = slots.get(index);
    for (int type = 0; type < 3; type++) {
      if (slot.key() != null && !slot.key().equals("o" + type)) {
        continue;
      }
      capacity[type] += slot.capacity() - slot.count();
      if (enumerate(slots, index + 1, wanted, capacity)) {
        return true;
      }
      capacity[type] -= slot.capacity() - slot.count();
    }
    return false;
  }

  private static ResourceSnapshot.OutputSlot<String> empty(long count) {
    return new ResourceSnapshot.OutputSlot<String>(null, count, 64);
  }
}
