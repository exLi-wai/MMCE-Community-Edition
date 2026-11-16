package com.lw.modularmachine.machine.recipe.search.index;

import java.util.BitSet;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.ApiStatus;

/** Binds an index to captured keys without losing the key type. */
@ApiStatus.Internal
public record RequiredInputQuery<K>(RequiredInputIndex<K> index, Set<K> available) implements ResourceInputQuery {
  public RequiredInputQuery {
    Objects.requireNonNull(index, "index");
    available = Set.copyOf(available);
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
}
