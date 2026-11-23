package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning.Progress;
import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning.Status;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecipeSearchSessionTest {
  private static final net.minecraft.resources.ResourceLocation REQUEST = net.minecraft.resources.ResourceLocation
      .withDefaultNamespace("request");

  @Test
  void unwatchedReplenishmentReadsMustFitThePublishingVisit() {
    var proof = new RecipeSearchResource.ReplenishmentProof();
    var reads = new java.util.concurrent.atomic.AtomicInteger();
    for (int i = 0; i < 3; i++) {
      proof.addLiveCheck(() -> {
        reads.incrementAndGet();
        return true;
      });
    }
    assertEquals(new Progress(Status.REJECTED, 0), proof.publishOne(REQUEST, 1, 1, () -> true, guard -> fail()));
    assertEquals(new Progress(Status.PENDING, 0), proof.publishOne(REQUEST, 1, 3, () -> true, guard -> fail()));
    assertEquals(0, reads.get());
    assertEquals(
        new Progress(Status.REJECTED, 1),
        proof.publishOne(REQUEST, 3, 3, () -> reads.get() == 0, guard -> fail()));
    assertEquals(new Progress(Status.READY, 3), proof.publishOne(REQUEST, 3, 3, () -> true, guard -> {
      assertTrue(guard.getAsBoolean());
      return new Progress(Status.READY, 1);
    }));
    assertEquals(4, reads.get());
  }

  @Test
  void severalKeysAtOneHatchShareOneFinalConnectionRead() {
    var proof = new RecipeSearchResource.ReplenishmentProof();
    var reads = new java.util.concurrent.atomic.AtomicInteger();
    var hatch = new Object();
    for (int key = 0; key < 3; key++) {
      proof.observeConnection(hatch, true, () -> reads.incrementAndGet() > 0);
    }
    assertEquals(new Progress(Status.READY, 1), proof.publishOne(REQUEST, 1, 1, () -> true, guard -> {
      assertTrue(guard.getAsBoolean());
      return new Progress(Status.READY, 1);
    }));
    assertEquals(1, reads.get());
    proof.observeConnection(hatch, false, () -> false);
    assertEquals(new Progress(Status.REJECTED, 0), proof.publishOne(REQUEST, 1, 1, () -> true, guard -> fail()));
  }

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
    var snapshot = new ResourceSnapshot<>(
        4,
        Map.of("dirt", 1L),
        List.of(new ResourceSnapshot.OutputSlot<>("b", 63, 64)));
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
        new ResourceSnapshot<>(0, Map.of("dirt", 1L), List.of(new ResourceSnapshot.OutputSlot<String>(null, 0, 64))),
        new CompiledRecipeSet<>(List.of(recipe("high", 10, "a", 1), recipe("low", 0, "b", 1))));
    assertEquals(RecipePlanner.Status.BUDGET_EXHAUSTED, session.advance(0).status());
    assertEquals(0, session.totalWork());
    assertThrows(IllegalArgumentException.class, () -> session.advance(-1));
    assertEquals("high", session.advance(1000).plan().orElseThrow().candidate().id());
  }

  @Test
  void unusableFilteredSlotsAreSkippedWithoutRejectingALaterUsableSlot() {
    var snapshot = new ResourceSnapshot<>(
        0,
        Map.of("dirt", 1L),
        List.of(
            new ResourceSnapshot.OutputSlot<String>(null, 0, 64, Map.of("unrelated", 64L)),
            new ResourceSnapshot.OutputSlot<String>(null, 0, 64, Map.of("a", 1L))));
    var plan = RecipePlanner.select(snapshot, List.of(recipe("a", 0, "a", 1))).orElseThrow();
    assertEquals(1, plan.insertions().getFirst().slot());
  }

  @Test
  void perKeyStackLimitsApplyToEmptyAndExistingSlots() {
    var candidate = recipe("pearls", 0, "pearl", 17);
    var one = new ResourceSnapshot<>(
        0,
        Map.of("dirt", 1L),
        List.of(new ResourceSnapshot.OutputSlot<String>(null, 0, 64, Map.of("pearl", 16L))));
    assertTrue(RecipePlanner.select(one, List.of(candidate)).isEmpty());
    var two = new ResourceSnapshot<>(
        0,
        Map.of("dirt", 1L),
        List.of(
            new ResourceSnapshot.OutputSlot<>("pearl", 15, 64, Map.of("pearl", 16L)),
            new ResourceSnapshot.OutputSlot<String>(null, 0, 64, Map.of("pearl", 16L))));
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
    var snapshot = new ResourceSnapshot<>(
        0,
        Map.of("dirt", 1L),
        List.of(
            new ResourceSnapshot.OutputSlot<String>(null, 0, 1, Map.of("a", 1L, "b", 1L)),
            new ResourceSnapshot.OutputSlot<String>(null, 0, 1, Map.of("a", 1L))));
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
    var outputs = new ArrayList<>(List.of(new ResourceSnapshot.OutputSlot<String>(null, 0, 64)));
    var source = new ArrayList<>(List.of(recipe("a", 1, "a", 1)));
    var catalog = new CompiledRecipeSet<>(source);
    var snapshot = new ResourceSnapshot<>(0, input, outputs);
    input.clear();
    outputs.clear();
    source.clear();
    assertEquals("a", RecipePlanner.prepare(snapshot, catalog).advance(1000).plan().orElseThrow().candidate().id());
    assertEquals("a", RecipePlanner.prepare(snapshot, catalog).advance(1000).plan().orElseThrow().candidate().id());
    assertThrows(UnsupportedOperationException.class, () -> catalog.outputKeys().clear());
  }

  @Test
  void outputSlotsShareTheirCapacityPolicyWithoutQueryingEveryProductDuringCapture() {
    var queries = new java.util.concurrent.atomic.AtomicInteger();
    ResourceSnapshot.SlotLimit<String> policy = (key, capacity) -> {
      queries.incrementAndGet();
      return key.equals("product") ? 1 : 0;
    };
    var slots = new ArrayList<ResourceSnapshot.OutputSlot<String>>();
    for (int index = 0; index < 100; index++) {
      slots.add(new ResourceSnapshot.OutputSlot<>(null, 0, 64, policy));
    }
    var snapshot = new ResourceSnapshot<>(1, Map.of(), slots);
    assertEquals(0, queries.get());
    assertEquals(1, snapshot.outputs().getFirst().capacityFor("product"));
    assertEquals(0, snapshot.outputs().getLast().capacityFor("other"));
    assertEquals(2, queries.get());
  }

  @Test
  void slotCountSupportLimitIsExplicitAndEmptyCatalogIsAProvenNoMatch() {
    var tooMany = Collections
        .nCopies(RecipePlanner.MAX_OUTPUT_SLOTS + 1, new ResourceSnapshot.OutputSlot<String>(null, 0, 64));
    assertThrows(
        IllegalArgumentException.class,
        () -> RecipePlanner.prepare(
            new ResourceSnapshot<>(0, Map.of("dirt", 1L), tooMany),
            new CompiledRecipeSet<>(List.of(recipe("a", 0, "a", 1)))));
    var empty = new CompiledRecipeSet<String>(List.of());
    assertEquals(
        RecipePlanner.Status.NO_MATCH,
        RecipePlanner.prepare(new ResourceSnapshot<String>(0, Map.of(), List.of()), empty).advance(0).status());
  }
}
