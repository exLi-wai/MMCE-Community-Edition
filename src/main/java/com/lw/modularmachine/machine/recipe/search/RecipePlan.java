package com.lw.modularmachine.machine.recipe.search;

import org.jetbrains.annotations.ApiStatus;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only reservation produced by a successful search; it has not changed
 * storage.
 */
@ApiStatus.Internal
public record RecipePlan<K>(
    long sourceRevision,
    RecipeCandidate<K> candidate,
    Map<K, Long> consumed,
    List<Insertion<K>> insertions) {
  public RecipePlan {
    if (sourceRevision < 0) {
      throw new IllegalArgumentException("sourceRevision must be non-negative");
    }
    Objects.requireNonNull(candidate, "candidate");
    consumed = Map.copyOf(consumed);
    insertions = List.copyOf(insertions);
    for (var entry : consumed.entrySet()) {
      if (entry.getKey() instanceof String text && text.isBlank() || entry.getValue() <= 0) {
        throw new IllegalArgumentException("Invalid consumption");
      }
    }
  }

  public record Insertion<K>(int slot, K key, long count) {
    public Insertion {
      Objects.requireNonNull(key, "key");
      if (slot < 0 || key instanceof String text && text.isBlank() || count <= 0) {
        throw new IllegalArgumentException("Invalid insertion");
      }
    }
  }
}
