package com.lw.modularmachine.machine.recipe.search.index;

import java.util.BitSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.ApiStatus;

/** Binds an index to captured keys without losing the key type. */
@ApiStatus.Internal
public final class RequiredInputQuery<K> implements ResourceInputQuery {
  private final RequiredInputIndex<K> index;
  private final Set<K> available;

  public RequiredInputQuery(RequiredInputIndex<K> index, Set<K> available) {
    this.index = Objects.requireNonNull(index, "index");
    this.available = Set.copyOf(available);
  }

  /**
   * Snapshot maps are already frozen by Map.copyOf, so their key views can be
   * shared.
   */
  public RequiredInputQuery(RequiredInputIndex<K> index, Map<K, ?> captured) {
    this.index = Objects.requireNonNull(index, "index");
    this.available = Map.copyOf(captured).keySet();
  }

  @Override
  public boolean hasAnchors() {
    return index.hasAnchors();
  }

  @Override
  public int candidateCount() {
    return index.candidateCount(available);
  }

  @Override
  public BitSet select(Set<String> allowedIds) {
    return index.select(available, allowedIds);
  }

  @Override
  public void retainMatches(BitSet selected) {
    index.retainMatches(selected, available);
  }

  @Override
  public boolean matchesRank(int rank) {
    var anchor = index.anchorKey(rank);
    return anchor == null || available.contains(anchor);
  }
}
