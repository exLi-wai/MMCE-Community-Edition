package com.lw.modularmachine.machine.recipe.search.index;

import java.util.BitSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.ApiStatus;

/**
 * Chooses the narrowest resource query, then intersects in the same rank space.
 */
@ApiStatus.Internal
public final class ResourceCandidateSelection {
  private ResourceCandidateSelection() {
  }

  public static BitSet select(List<? extends ResourceInputQuery> queries, Set<String> allowedIds) {
    if (queries.isEmpty()) {
      throw new IllegalArgumentException("A catalog query is required");
    }
    var source = queries.getFirst();
    if (allowedIds == null || allowedIds.size() > 1) {
      int minimum = source.candidateCount();
      for (int index = 1; index < queries.size(); index++) {
        var query = queries.get(index);
        if (query.hasAnchors()) {
          int count = query.candidateCount();
          if (count < minimum) {
            source = query;
            minimum = count;
          }
        }
      }
    }
    var selected = source.select(allowedIds);
    for (var query : queries) {
      if (query != source && !selected.isEmpty()) {
        query.retainMatches(selected);
      }
    }

    return selected;
  }
}
