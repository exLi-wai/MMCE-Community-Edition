package com.lw.modularmachine.machine.recipe.search.index;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable necessary-input anchors shared by resource-specific adapters and
 * the generic planner. Keys must be immutable, and every supplied key must be
 * required for its recipe. No quantities, resource access or production
 * authority live here.
 */
@ApiStatus.Internal
public final class RequiredInputIndex<K> {
  private final List<Anchor<K>> anchors;
  private final Map<String, Integer> ranksById;
  private final Map<K, List<Integer>> ranksByAnchor;
  private final List<Integer> ranksWithoutAnchor;

  /**
   * The list order must be the owning catalog's priority/ID order. Resource
   * indexes combined in one query must use that same catalog and rank space.
   */
  public RequiredInputIndex(List<RecipeInputs<K>> recipes) {
    var frequency = new HashMap<K, Integer>();
    for (var recipe : recipes) {
      for (var key : recipe.keys()) {
        frequency.merge(key, 1, Math::addExact);
      }
    }
    var compiled = new ArrayList<Anchor<K>>(recipes.size());
    var ids = new HashMap<String, Integer>();
    var anchored = new HashMap<K, List<Integer>>();
    var unanchored = new ArrayList<Integer>();
    for (var recipe : recipes) {
      int rank = compiled.size();
      if (ids.put(recipe.recipeId(), rank) != null) {
        throw new IllegalArgumentException("Duplicate recipe ID: " + recipe.recipeId());
      }
      K anchor = null;
      for (var key : recipe.keys()) {
        if (anchor == null || frequency.get(key) < frequency.get(anchor)) {
          anchor = key;
        }
      }
      compiled.add(new Anchor<>(recipe.recipeId(), anchor));
      if (anchor == null) {
        unanchored.add(rank);
      } else {
        anchored.computeIfAbsent(anchor, ignored -> new ArrayList<>()).add(rank);
      }
    }
    var frozen = new HashMap<K, List<Integer>>();
    anchored.forEach((key, ranks) -> frozen.put(key, List.copyOf(ranks)));
    anchors = List.copyOf(compiled);
    ranksById = Map.copyOf(ids);
    ranksByAnchor = Map.copyOf(frozen);
    ranksWithoutAnchor = List.copyOf(unanchored);
  }

  public boolean hasAnchors() {
    return !ranksByAnchor.isEmpty();
  }

  /** Null means this resource cannot exclude the recipe by an input anchor. */
  public @Nullable K anchorKey(int rank) {
    return anchors.get(rank).key();
  }

  public int candidateCount(Set<K> available) {
    int count = ranksWithoutAnchor.size();
    for (var key : available) {
      count += ranksByAnchor.getOrDefault(key, List.of()).size();
    }

    return count;
  }

  /**
   * Returns a new query-owned bitmap. Null allowedIds means all catalog recipes;
   * an empty set means none. Key presence is only a necessary condition.
   */
  public BitSet select(Set<K> available, Set<String> allowedIds) {
    var selected = new BitSet(anchors.size());
    if (allowedIds != null && preferIdLookup(available, allowedIds.size())) {
      for (var id : allowedIds) {
        var rank = ranksById.get(id);
        if (rank != null && matches(rank, available)) {
          selected.set(rank);
        }
      }
      return selected;
    }
    for (int rank : ranksWithoutAnchor) {
      if (allowedIds == null || allowedIds.contains(anchors.get(rank).recipeId())) {
        selected.set(rank);
      }
    }
    for (var key : available) {
      for (int rank : ranksByAnchor.getOrDefault(key, List.of())) {
        if (allowedIds == null || allowedIds.contains(anchors.get(rank).recipeId())) {
          selected.set(rank);
        }
      }
    }

    return selected;
  }

  /**
   * Intersects a query-owned bitmap from the same catalog with this resource's
   * necessary condition. No-input recipes pass; stored index data never changes.
   */
  public void retainMatches(BitSet selected, Set<K> available) {
    if (!hasAnchors()) {
      return;
    }
    for (int rank = selected.nextSetBit(0); rank >= 0; rank = selected.nextSetBit(rank + 1)) {
      if (!matches(rank, available)) {
        selected.clear(rank);
      }
    }
  }

  private boolean matches(int rank, Set<K> available) {
    var anchor = anchorKey(rank);

    return anchor == null || available.contains(anchor);
  }

  private boolean preferIdLookup(Set<K> available, int allowedCount) {
    if (allowedCount <= 1 || allowedCount <= ranksWithoutAnchor.size()) {
      return true;
    }
    long indexedCount = ranksWithoutAnchor.size();
    for (var key : available) {
      indexedCount += ranksByAnchor.getOrDefault(key, List.of()).size();
      if (indexedCount >= allowedCount) {
        return true;
      }
    }

    return false;
  }

  /** Distinct required keys retain declaration order for equal-frequency ties. */
  public record RecipeInputs<K>(String recipeId, List<K> keys) {
    public RecipeInputs {
      if (recipeId == null || recipeId.isBlank()) {
        throw new IllegalArgumentException("Recipe id is required");
      }
      keys = List.copyOf(new LinkedHashSet<>(keys));
    }
  }

  private record Anchor<K>(String recipeId, K key) {
  }
}
