package com.lw.modularmachine.machine.recipe.search.index;

import java.util.BitSet;
import java.util.Set;
import org.jetbrains.annotations.ApiStatus;

/**
 * One immutable necessary-condition query in its owning catalog's rank space.
 */
@ApiStatus.Internal
public interface ResourceInputQuery {
  boolean hasAnchors();

  int candidateCount();

  BitSet select(Set<String> allowedIds);

  void retainMatches(BitSet selected);

  default boolean matchesRank(int rank) {
    var single = new BitSet();
    single.set(rank);
    retainMatches(single);
    return single.get(rank);
  }
}
