package com.lw.modularmachine.machine.recipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RecipeSearchSessionTest {
  private static RecipeCandidate<String> recipe(String id, int priority, String output, long count) {
    return new RecipeCandidate<>(
        id,
        priority,
        List.of(new RecipeCandidate.Input<>("dirt", 1)),
        List.of(new RecipeCandidate.Output<>(output, count)));
  }

  @Test
  void oneUnitSlicesResumeTheSameSearchAndReturnTheOneShotPlan() {
    var candidates = new CompiledRecipeSet<>(List.of(recipe("a", 10, "a", 2), recipe("b", 1, "b", 1)));
    var snapshot = new ItemResourceSnapshot<>(
        4,
        Map.of("dirt", 1L),
        List.of(new ItemResourceSnapshot.OutputSlot<>("b", 63, 64)));
    var whole = RecipePlanner.prepare(snapshot, candidates).advance(1000);
    var sliced = RecipePlanner.prepare(snapshot, candidates);
    RecipePlanner.SearchResult<String> result;
    int calls = 0;
    do {
      result = sliced.advance(1);
      assertTrue(result.workUsed() <= 1);
      assertTrue(++calls < 1000, "Session restarted instead of progressing");
    } while (result.status() == RecipePlanner.Status.BUDGET_EXHAUSTED);
    assertEquals(whole.plan(), result.plan());
    assertEquals("b", result.plan().orElseThrow().candidate().id());
    assertEquals(whole.workUsed(), sliced.totalWork());
    assertEquals(0, sliced.advance(1000).workUsed());
    assertEquals(result.plan(), sliced.advance(1).plan());
  }

  @Test
  void zeroBudgetDoesNoWorkAndCannotSkipAHigherPriorityCandidate() {
    var session = RecipePlanner.prepare(
        new ItemResourceSnapshot<>(
            0,
            Map.of("dirt", 1L),
            List.of(new ItemResourceSnapshot.OutputSlot<String>(null, 0, 64))),
        new CompiledRecipeSet<>(List.of(recipe("high", 10, "a", 1), recipe("low", 0, "b", 1))));
    assertEquals(RecipePlanner.Status.BUDGET_EXHAUSTED, session.advance(0).status());
    assertEquals(0, session.totalWork());
    assertThrows(IllegalArgumentException.class, () -> session.advance(-1));
    assertEquals("high", session.advance(1000).plan().orElseThrow().candidate().id());
  }

  @Test
  void unusableFilteredSlotsAreSkippedWithoutRejectingALaterUsableSlot() {
    var snapshot = new ItemResourceSnapshot<>(
        0,
        Map.of("dirt", 1L),
        List.of(
            new ItemResourceSnapshot.OutputSlot<String>(null, 0, 64, Map.of("unrelated", 64L)),
            new ItemResourceSnapshot.OutputSlot<String>(null, 0, 64, Map.of("a", 1L))));
    var plan = RecipePlanner.select(snapshot, List.of(recipe("a", 0, "a", 1))).orElseThrow();
    assertEquals(1, plan.insertions().getFirst().slot());
  }

  @Test
  void perKeyStackLimitsApplyToEmptyAndExistingSlots() {
    var candidate = recipe("pearls", 0, "pearl", 17);
    var one = new ItemResourceSnapshot<>(
        0,
        Map.of("dirt", 1L),
        List.of(new ItemResourceSnapshot.OutputSlot<String>(null, 0, 64, Map.of("pearl", 16L))));
    assertTrue(RecipePlanner.select(one, List.of(candidate)).isEmpty());
    var two = new ItemResourceSnapshot<>(
        0,
        Map.of("dirt", 1L),
        List.of(
            new ItemResourceSnapshot.OutputSlot<>("pearl", 15, 64, Map.of("pearl", 16L)),
            new ItemResourceSnapshot.OutputSlot<String>(null, 0, 64, Map.of("pearl", 16L))));
    assertEquals(
        List.of(1L, 16L),
        RecipePlanner.select(two, List.of(candidate)).orElseThrow().insertions().stream()
            .map(RecipePlan.Insertion::count).toList());
  }

  @Test
  void perKeyRestrictionsStillBacktrackForAnExactAssignment() {
    var candidate = new RecipeCandidate<>(
        "pair",
        0,
        List.of(new RecipeCandidate.Input<>("dirt", 1)),
        List.of(new RecipeCandidate.Output<>("a", 1), new RecipeCandidate.Output<>("b", 1)));
    var snapshot = new ItemResourceSnapshot<>(
        0,
        Map.of("dirt", 1L),
        List.of(
            new ItemResourceSnapshot.OutputSlot<String>(null, 0, 1, Map.of("a", 1L, "b", 1L)),
            new ItemResourceSnapshot.OutputSlot<String>(null, 0, 1, Map.of("a", 1L))));
    var plan = RecipePlanner.select(snapshot, List.of(candidate)).orElseThrow();
    assertEquals("b", plan.insertions().stream().filter(i -> i.slot() == 0).findFirst().orElseThrow().key());
  }

  @Test
  void compilationRejectsDuplicateIdsAndInputOrOutputOverflow() {
    var one = recipe("same", 1, "a", 1);
    assertThrows(IllegalArgumentException.class, () -> new CompiledRecipeSet<>(List.of(one, one)));
    var bad = new RecipeCandidate<>(
        "overflow",
        0,
        List.of(new RecipeCandidate.Input<>("dirt", 1)),
        List.of(new RecipeCandidate.Output<>("a", Long.MAX_VALUE), new RecipeCandidate.Output<>("a", 1)));
    assertThrows(IllegalArgumentException.class, () -> new CompiledRecipeSet<>(List.of(bad)));
  }

  @Test
  void reusableCatalogAndDefensiveSnapshotDoNotFollowMutableSourceCollections() {
    var input = new HashMap<>(Map.of("dirt", 1L));
    var outputs = new ArrayList<>(List.of(new ItemResourceSnapshot.OutputSlot<String>(null, 0, 64)));
    var source = new ArrayList<>(List.of(recipe("a", 1, "a", 1)));
    var catalog = new CompiledRecipeSet<>(source);
    var snapshot = new ItemResourceSnapshot<>(0, input, outputs);
    input.clear();
    outputs.clear();
    source.clear();
    assertEquals("a", RecipePlanner.prepare(snapshot, catalog).advance(1000).plan().orElseThrow().candidate().id());
    assertEquals("a", RecipePlanner.prepare(snapshot, catalog).advance(1000).plan().orElseThrow().candidate().id());
    assertThrows(UnsupportedOperationException.class, () -> catalog.outputKeys().clear());
  }

  @Test
  void slotCountSupportLimitIsExplicitAndEmptyCatalogIsAProvenNoMatch() {
    var tooMany = Collections
        .nCopies(RecipePlanner.MAX_OUTPUT_SLOTS + 1, new ItemResourceSnapshot.OutputSlot<String>(null, 0, 64));
    assertThrows(
        IllegalArgumentException.class,
        () -> RecipePlanner.prepare(
            new ItemResourceSnapshot<>(0, Map.of("dirt", 1L), tooMany),
            new CompiledRecipeSet<>(List.of(recipe("a", 0, "a", 1)))));
    var empty = new CompiledRecipeSet<String>(List.of());
    assertEquals(
        RecipePlanner.Status.NO_MATCH,
        RecipePlanner.prepare(new ItemResourceSnapshot<String>(0, Map.of(), List.of()), empty).advance(0).status());
  }
}
