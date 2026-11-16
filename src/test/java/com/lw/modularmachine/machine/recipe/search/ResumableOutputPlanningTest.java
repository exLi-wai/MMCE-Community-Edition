package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResumableOutputPlanningTest {
  @Test
  void feasibleOutputLargerThanOldFixedBudgetResumesAndSharesWorkWithNextResource() {
    var claims = new ArrayList<RecipeCandidate.Output<String>>();
    for (int key = 0; key < 48; key++) {
      claims.add(new RecipeCandidate.Output<>("product_" + key, 1));
    }
    var slots = new ArrayList<ResourceSnapshot.OutputSlot<String>>();
    for (int slot = 0; slot < 2048 - 47; slot++) {
      slots.add(new ResourceSnapshot.OutputSlot<>(null, 0, 64, Map.of("product_0", 64L)));
    }
    for (int key = 1; key < 48; key++) {
      slots.add(new ResourceSnapshot.OutputSlot<>(null, 0, 64, Map.of("product_" + key, 64L)));
    }
    var itemSnapshot = new ResourceSnapshot<>(0, Map.<String, Long>of(), slots);
    var itemSession = RecipePlanner.prepareOutputs(itemSnapshot, RecipeCandidate.outputOnly("items", claims));
    var fluidSnapshot = new ResourceSnapshot<String>(
        0,
        Map.of(),
        List.of(new ResourceSnapshot.OutputSlot<>(null, 0, 1000)));
    var fluidSession = RecipePlanner.prepareOutputs(
        fluidSnapshot,
        RecipeCandidate.outputOnly("fluids", List.of(new RecipeCandidate.Output<>("water", 1000))));
    var itemsCreated = new AtomicInteger();
    var fluidsCreated = new AtomicInteger();
    var sequence = new ResourcePlanningSequence(List.of(() -> {
      itemsCreated.incrementAndGet();
      return new ResourcePlanningAdapter<>(() -> itemSession.advance(0).status(), itemSession::advance);
    }, () -> {
      fluidsCreated.incrementAndGet();
      return new ResourcePlanningAdapter<>(() -> fluidSession.advance(0).status(), fluidSession::advance);
    }));

    assertEquals(new RecipeResourcePlanning.Progress(RecipeResourcePlanning.Status.PENDING, 0), sequence.advance(0));
    assertEquals(0, itemsCreated.get());
    var first = sequence.advance(65_536);
    assertEquals(new RecipeResourcePlanning.Progress(RecipeResourcePlanning.Status.PENDING, 65_536), first);
    assertEquals(1, itemsCreated.get());
    assertEquals(0, fluidsCreated.get());
    long used = first.workUsed();
    int slices = 0;
    while (sequence.status() == RecipeResourcePlanning.Status.PENDING && slices++ < 1000) {
      var progress = sequence.advance(512);
      assertTrue(progress.workUsed() <= 512);
      used += progress.workUsed();
    }
    assertEquals(RecipeResourcePlanning.Status.READY, sequence.status());
    assertEquals(itemSession.totalWork() + fluidSession.totalWork(), used);
    assertEquals(1, itemsCreated.get());
    assertEquals(1, fluidsCreated.get());
    assertEquals(48, itemSession.advance(0).plan().orElseThrow().insertions().size());
    assertEquals(1000, fluidSession.advance(0).plan().orElseThrow().insertions().getFirst().count());
    assertEquals(0, sequence.advance(512).workUsed());
  }

  @Test
  void resourceTransitionUsesOnlyTheUnspentBudget() {
    var snapshot = new ResourceSnapshot<String>(0, Map.of(), List.of(new ResourceSnapshot.OutputSlot<>(null, 0, 64)));
    var candidate = RecipeCandidate.outputOnly("one", List.of(new RecipeCandidate.Output<>("a", 1)));
    var baseline = RecipePlanner.prepareOutputs(snapshot, candidate).advance(100);
    assertEquals(RecipePlanner.Status.SELECTED, baseline.status());
    var first = RecipePlanner.prepareOutputs(snapshot, candidate);
    var second = RecipePlanner.prepareOutputs(snapshot, candidate);
    var sequence = new ResourcePlanningSequence(
        List.of(
            () -> new ResourcePlanningAdapter<>(() -> first.advance(0).status(), first::advance),
            () -> new ResourcePlanningAdapter<>(() -> second.advance(0).status(), second::advance)));
    int budget = baseline.workUsed() + 2;
    var result = sequence.advance(budget);
    assertEquals(RecipeResourcePlanning.Status.PENDING, result.status());
    assertEquals(budget, result.workUsed());
    assertEquals(baseline.workUsed(), first.totalWork());
    assertEquals(2, second.totalWork());
    assertEquals(RecipeResourcePlanning.Status.READY, sequence.advance(100).status());
  }
}
