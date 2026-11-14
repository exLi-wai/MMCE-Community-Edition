package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.machine.recipe.search.index.RequiredInputIndex;
import org.jetbrains.annotations.ApiStatus;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable, reusable candidate order and checked exact-key demand totals. No
 * registry/world reads.
 */
@ApiStatus.Internal
public final class CompiledRecipeSet<K> {
  public static final int MAX_CANDIDATES = 4096;
  public static final int MAX_TOTAL_REQUIREMENTS = 65_536;
  public static final int MAX_DISTINCT_OUTPUTS = 256;
  private final List<Entry<K>> entries;
  private final List<RecipeCandidate<K>> candidates;
  private final Set<K> outputKeys;
  private final Map<String, Entry<K>> byId;
  private final RequiredInputIndex<K> inputIndex;

  public CompiledRecipeSet(Collection<RecipeCandidate<K>> candidates) {
    if (candidates.size() > MAX_CANDIDATES) {
      throw new IllegalArgumentException("Too many recipe candidates");
    }
    var ids = new HashSet<String>();
    var pending = new ArrayList<Pending<K>>();
    var keys = new HashSet<K>();
    int requirements = 0;
    for (var recipe : candidates) {
      if (!ids.add(recipe.id())) {
        throw new IllegalArgumentException("Duplicate recipe ID: " + recipe.id());
      }
      requirements = Math.addExact(requirements, Math.addExact(recipe.inputs().size(), recipe.outputs().size()));
      if (requirements > MAX_TOTAL_REQUIREMENTS) {
        throw new IllegalArgumentException("Too many recipe requirements");
      }
      var input = new LinkedHashMap<K, Long>();
      var output = new LinkedHashMap<K, Long>();
      try {
        for (var demand : recipe.inputs()) {
          input.merge(demand.key(), demand.count(), Math::addExact);
        }
        for (var demand : recipe.outputs()) {
          output.merge(demand.key(), demand.count(), Math::addExact);
        }
      } catch (ArithmeticException overflow) {
        throw new IllegalArgumentException("Demand overflow in recipe " + recipe.id(), overflow);
      }
      if (output.size() > MAX_DISTINCT_OUTPUTS) {
        throw new IllegalArgumentException("Too many distinct output keys");
      }
      pending.add(new Pending<>(recipe, input, output));
      keys.addAll(output.keySet());
    }
    pending.sort(
        Comparator.<Pending<K>>comparingInt(entry -> entry.recipe().priority()).reversed()
            .thenComparing(entry -> entry.recipe().id()));
    inputIndex = new RequiredInputIndex<>(
        pending.stream().map(
            entry -> new RequiredInputIndex.RecipeInputs<>(entry.recipe().id(), List.copyOf(entry.input().keySet())))
            .toList());
    var compiled = new ArrayList<Entry<K>>(pending.size());
    for (var entry : pending) {
      int rank = compiled.size();
      compiled.add(new Entry<>(rank, entry.recipe(), entry.input(), entry.output(), inputIndex.anchorKey(rank)));
    }
    entries = List.copyOf(compiled);
    this.candidates = entries.stream().map(entry -> entry.recipe).toList();
    outputKeys = Set.copyOf(keys);
    var indexed = new HashMap<String, Entry<K>>();
    for (var entry : entries) {
      indexed.put(entry.recipe.id(), entry);
    }
    byId = Map.copyOf(indexed);
  }

  public Set<K> outputKeys() {
    return outputKeys;
  }

  public int size() {
    return entries.size();
  }

  public List<RecipeCandidate<K>> candidates() {
    return candidates;
  }

  /** Immutable necessary-key index shared with the matching resource adapter. */
  public RequiredInputIndex<K> inputIndex() {
    return inputIndex;
  }

  /**
   * Returns candidates restricted by stable IDs without rebuilding or sorting the
   * compiled catalog. ID lookups do not scan unrelated recipes.
   */
  List<RecipeCandidate<K>> candidatesFor(Set<String> allowedIds) {
    if (allowedIds == null) {
      return candidates();
    }
    if (allowedIds.isEmpty()) {
      return List.of();
    }
    if (allowedIds.size() == 1) {
      var entry = byId.get(allowedIds.iterator().next());
      return entry == null ? List.of() : List.of(entry.recipe);
    }
    var selected = new BitSet(entries.size());
    for (var id : allowedIds) {
      var entry = byId.get(id);
      if (entry != null) {
        selected.set(entry.rank);
      }
    }

    return candidatesAt(selected);
  }

  /** Only strict predecessors can improve an already complete group result. */
  Set<String> candidateIdsBefore(String recipeId, Set<String> allowedIds) {
    var limit = byId.get(recipeId);
    if (limit == null) {
      throw new IllegalArgumentException("Unknown recipe ID: " + recipeId);
    }
    if (limit.rank == 0 || allowedIds != null && allowedIds.isEmpty()) {
      return Set.of();
    }
    var selected = new HashSet<String>();
    if (allowedIds == null) {
      for (int rank = 0; rank < limit.rank; rank++) {
        selected.add(entries.get(rank).recipe.id());
      }
    } else {
      for (var id : allowedIds) {
        var entry = byId.get(id);
        if (entry != null && entry.rank < limit.rank) {
          selected.add(id);
        }
      }
    }

    return Set.copyOf(selected);
  }

  public Optional<RecipeCandidate<K>> candidate(String id) {
    return Optional.ofNullable(byId.get(id)).map(entry -> entry.recipe);
  }

  List<Entry<K>> entries() {
    return entries;
  }

  List<Entry<K>> entriesFor(ResourceSnapshot<K> snapshot, boolean outputOnly) {
    return entriesFor(snapshot, outputOnly, null);
  }

  List<Entry<K>> entriesFor(ResourceSnapshot<K> snapshot, boolean outputOnly, Set<String> allowedIds) {
    if (outputOnly || entries.isEmpty()) {
      return entries;
    }
    var selected = inputIndex.select(snapshot.inputs().keySet(), allowedIds);
    var result = new ArrayList<Entry<K>>(selected.cardinality());
    for (int rank = selected.nextSetBit(0); rank >= 0; rank = selected.nextSetBit(rank + 1)) {
      result.add(entries.get(rank));
    }

    return List.copyOf(result);
  }

  /**
   * Materializes query ranks from this catalog in its stable priority/ID order.
   */
  List<RecipeCandidate<K>> candidatesAt(BitSet selected) {
    var result = new ArrayList<RecipeCandidate<K>>(selected.cardinality());
    for (int rank = selected.nextSetBit(0); rank >= 0; rank = selected.nextSetBit(rank + 1)) {
      result.add(entries.get(rank).recipe);
    }

    return List.copyOf(result);
  }

  BitSet ranksOf(List<RecipeCandidate<K>> selected) {
    var ranks = new BitSet(entries.size());
    for (var candidate : selected) {
      var entry = byId.get(candidate.id());
      if (entry != null) {
        ranks.set(entry.rank);
      }
    }
    return ranks;
  }

  static final class Entry<K> {
    final int rank;
    final RecipeCandidate<K> recipe;
    final Map<K, Long> input;
    final List<Map.Entry<K, Long>> inputEntries;
    final List<K> outputKeys;
    final Map<K, Integer> outputIndices;
    final K anchorKey;
    final long anchorAmount;
    private final long[] amounts;

    Entry(int rank, RecipeCandidate<K> recipe, Map<K, Long> input, Map<K, Long> output, K anchor) {
      this.rank = rank;
      this.recipe = recipe;
      this.input = Map.copyOf(input);
      inputEntries = input.entrySet().stream().map(entry -> Map.entry(entry.getKey(), entry.getValue())).toList();
      anchorKey = anchor;
      anchorAmount = anchor == null ? 0 : input.get(anchor);
      outputKeys = List.copyOf(output.keySet());
      var indices = new LinkedHashMap<K, Integer>();
      for (int i = 0; i < outputKeys.size(); i++) {
        indices.put(outputKeys.get(i), i);
      }
      outputIndices = Map.copyOf(indices);
      amounts = output.values().stream().mapToLong(Long::longValue).toArray();
    }

    long[] outputAmounts() {
      return amounts.clone();
    }
  }

  private record Pending<K>(RecipeCandidate<K> recipe, Map<K, Long> input, Map<K, Long> output) {
  }
}
