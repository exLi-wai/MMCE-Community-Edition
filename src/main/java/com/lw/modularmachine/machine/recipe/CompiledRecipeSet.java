package com.lw.modularmachine.machine.recipe;

import java.util.ArrayList;
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
public final class CompiledRecipeSet<K> {
  public static final int MAX_CANDIDATES = 4096;
  public static final int MAX_TOTAL_REQUIREMENTS = 65_536;
  public static final int MAX_DISTINCT_OUTPUTS = 256;
  private final List<Entry<K>> entries;
  private final Set<K> outputKeys;
  private final Map<String, RecipeCandidate<K>> byId;
  private final Map<K, List<Entry<K>>> entriesByAnchor;
  private final List<Entry<K>> entriesWithoutAnchor;

  public CompiledRecipeSet(Collection<RecipeCandidate<K>> candidates) {
    if (candidates.size() > MAX_CANDIDATES) {
      throw new IllegalArgumentException("Too many recipe candidates");
    }
    var ids = new HashSet<String>();
    var pending = new ArrayList<Pending<K>>();
    var keys = new HashSet<K>();
    var inputFrequency = new HashMap<K, Integer>();
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
      for (var key : input.keySet()) {
        inputFrequency.merge(key, 1, Math::addExact);
      }
      pending.add(new Pending<>(recipe, input, output));
      keys.addAll(output.keySet());
    }
    pending.sort(
        Comparator.<Pending<K>>comparingInt(entry -> entry.recipe().priority()).reversed()
            .thenComparing(entry -> entry.recipe().id()));
    var compiled = new ArrayList<Entry<K>>(pending.size());
    for (var entry : pending) {
      compiled.add(new Entry<>(entry.recipe(), entry.input(), entry.output(), inputFrequency));
    }
    entries = List.copyOf(compiled);
    outputKeys = Set.copyOf(keys);
    var anchored = new HashMap<K, List<Entry<K>>>();
    var unanchored = new ArrayList<Entry<K>>();
    for (var entry : entries) {
      if (entry.anchorKey == null) {
        unanchored.add(entry);
      } else {
        anchored.computeIfAbsent(entry.anchorKey, ignored -> new ArrayList<>()).add(entry);
      }
    }
    var frozenAnchored = new HashMap<K, List<Entry<K>>>();
    anchored.forEach((key, value) -> frozenAnchored.put(key, List.copyOf(value)));
    entriesByAnchor = Map.copyOf(frozenAnchored);
    entriesWithoutAnchor = List.copyOf(unanchored);
    var indexed = new LinkedHashMap<String, RecipeCandidate<K>>();
    for (var entry : entries) {
      indexed.put(entry.recipe.id(), entry.recipe);
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
    return entries.stream().map(entry -> entry.recipe).toList();
  }

  /**
   * Returns candidates restricted by stable IDs without rebuilding or sorting the
   * compiled catalog. The single-ID path avoids scanning unrelated recipes.
   */
  List<RecipeCandidate<K>> candidatesFor(Set<String> allowedIds) {
    if (allowedIds == null) {
      return candidates();
    }
    if (allowedIds.isEmpty()) {
      return List.of();
    }
    if (allowedIds.size() == 1) {
      String id = allowedIds.iterator().next();
      return byId.containsKey(id) ? List.of(byId.get(id)) : List.of();
    }
    return entries.stream().filter(entry -> allowedIds.contains(entry.recipe.id())).map(entry -> entry.recipe).toList();
  }

  public Optional<RecipeCandidate<K>> candidate(String id) {
    return Optional.ofNullable(byId.get(id));
  }

  List<Entry<K>> entries() {
    return entries;
  }

  List<Entry<K>> entriesFor(ItemResourceSnapshot<K> snapshot, boolean outputOnly) {
    return entriesFor(snapshot, outputOnly, null);
  }

  List<Entry<K>> entriesFor(ItemResourceSnapshot<K> snapshot, boolean outputOnly, Set<String> allowedIds) {
    if (outputOnly || entries.isEmpty()) {
      return entries;
    }
    var selected = new HashSet<Entry<K>>(entriesWithoutAnchor);
    for (var key : snapshot.inputs().keySet()) {
      selected.addAll(entriesByAnchor.getOrDefault(key, List.of()));
    }
    return entries.stream().filter(selected::contains)
        .filter(entry -> allowedIds == null || allowedIds.contains(entry.recipe.id())).toList();
  }

  static final class Entry<K> {
    final RecipeCandidate<K> recipe;
    final Map<K, Long> input;
    final List<Map.Entry<K, Long>> inputEntries;
    final List<K> outputKeys;
    final Map<K, Integer> outputIndices;
    final K anchorKey;
    final long anchorAmount;
    private final long[] amounts;

    Entry(RecipeCandidate<K> recipe, Map<K, Long> input, Map<K, Long> output, Map<K, Integer> inputFrequency) {
      this.recipe = recipe;
      this.input = Map.copyOf(input);
      inputEntries = input.entrySet().stream().map(entry -> Map.entry(entry.getKey(), entry.getValue())).toList();
      var anchor = input.entrySet().stream().min(Comparator.comparingInt(entry -> inputFrequency.get(entry.getKey())))
          .orElse(null);
      anchorKey = anchor == null ? null : anchor.getKey();
      anchorAmount = anchor == null ? 0 : anchor.getValue();
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
