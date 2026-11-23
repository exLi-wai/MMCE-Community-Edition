package com.lw.modularmachine.machine.recipe.search.index;

import com.lw.modularmachine.machine.recipe.search.ResourceSnapshot;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RequiredInputIndexSparseTest {
  @Test
  void largeInventoryIsProbedOnlyForDistinctAnchors() {
    var index = new RequiredInputIndex<>(
        List.of(
            new RequiredInputIndex.RecipeInputs<>("missing", List.of(9999)),
            new RequiredInputIndex.RecipeInputs<>("free", List.of()),
            new RequiredInputIndex.RecipeInputs<>("a", List.of(7)),
            new RequiredInputIndex.RecipeInputs<>("b", List.of(7))));
    var inventory = new CountingSet(IntStream.range(0, 4096).boxed().collect(Collectors.toSet()));
    assertEquals(3, index.candidateCount(inventory));
    assertArrayEquals(new int[]{1, 2, 3}, index.select(inventory, null).stream().toArray());
    assertEquals(0, inventory.iterations);
    assertEquals(4, inventory.probes);
    assertArrayEquals(new int[]{1, 3}, index.select(inventory, Set.of("free", "b")).stream().toArray());
    assertEquals(5, inventory.probes);
    assertTrue(index.select(inventory, Set.of()).isEmpty());
  }

  @Test
  void sparseInventoryUsesItsKeysAndLockedRecipesUseOneIdProbe() {
    var recipes = new ArrayList<RequiredInputIndex.RecipeInputs<Integer>>();
    for (int key = 0; key < 2048; key++) {
      recipes.add(new RequiredInputIndex.RecipeInputs<>("r" + key, List.of(key)));
    }
    var index = new RequiredInputIndex<>(recipes);
    var inventory = new CountingSet(Set.of(101));
    assertEquals(1, index.candidateCount(inventory));
    assertArrayEquals(new int[]{101}, index.select(inventory, null).stream().toArray());
    assertEquals(2, inventory.iterations);
    assertEquals(0, inventory.probes);
    assertTrue(index.select(inventory, Set.of("r102")).isEmpty());
    assertEquals(1, inventory.probes);
    assertEquals(2, inventory.iterations);
  }

  @Test
  void selectingFromCapturedSnapshotHashesOnlyAnchorKeys() {
    var counts = new HashMap<CountingKey, Long>();
    var keys = IntStream.range(0, 4096).mapToObj(CountingKey::new).toList();
    keys.forEach(key -> counts.put(key, 1L));
    var snapshot = new ResourceSnapshot<>(1, counts, List.of());
    var anchor = keys.get(7);
    var index = new RequiredInputIndex<>(List.of(new RequiredInputIndex.RecipeInputs<>("a", List.of(anchor))));
    keys.forEach(key -> key.hashCalls = 0);

    var query = new RequiredInputQuery<>(index, snapshot.inputs());
    assertArrayEquals(new int[]{0}, ResourceCandidateSelection.select(List.of(query), null).stream().toArray());
    assertTrue(anchor.hashCalls > 0);
    assertTrue(keys.stream().filter(key -> key != anchor).allMatch(key -> key.hashCalls == 0));
  }

  @Test
  void queriesKeepTheirOwnSnapshotOfMutableInputs() {
    var index = new RequiredInputIndex<>(List.of(new RequiredInputIndex.RecipeInputs<>("a", List.of(7))));
    var mutableMap = new HashMap<Integer, Long>();
    mutableMap.put(7, 1L);
    var mutableSet = new HashSet<>(mutableMap.keySet());
    var fromMap = new RequiredInputQuery<>(index, mutableMap);
    var fromSet = new RequiredInputQuery<>(index, mutableSet);
    mutableMap.clear();
    mutableSet.clear();
    assertEquals(1, fromMap.candidateCount());
    assertEquals(1, fromSet.candidateCount());
  }

  private static final class CountingKey {
    private final int value;
    private int hashCalls;

    CountingKey(int value) {
      this.value = value;
    }

    @Override
    public int hashCode() {
      hashCalls++;
      return value;
    }
  }

  private static final class CountingSet extends AbstractSet<Integer> {
    private final Set<Integer> keys;
    int iterations, probes;

    CountingSet(Set<Integer> keys) {
      this.keys = keys;
    }

    @Override
    public Iterator<Integer> iterator() {
      iterations++;
      return keys.iterator();
    }

    @Override
    public boolean contains(Object value) {
      probes++;
      return keys.contains(value);
    }

    @Override
    public int size() {
      return keys.size();
    }
  }
}
