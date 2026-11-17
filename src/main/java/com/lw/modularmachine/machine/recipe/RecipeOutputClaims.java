package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.recipe.search.RecipeCandidate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.ApiStatus;

/**
 * Required totals stay exact; explicit overflow output quantities retain
 * lossless long-sized segments.
 */
@ApiStatus.Internal
public final class RecipeOutputClaims<K> {
  private final Map<K, Long> required = new LinkedHashMap<>();
  private final List<RecipeCandidate.Output<K>> optional = new ArrayList<>();

  public void add(K key, long amount, boolean ignored) {
    if (amount <= 0) {
      throw new IllegalArgumentException("Output claims require a positive quantity");
    }
    if (!ignored) {
      required.merge(key, amount, Math::addExact);
      return;
    }
    if (optional.isEmpty() || !optional.getLast().key().equals(key)) {
      optional.add(new RecipeCandidate.Output<>(key, amount, false, true));
      return;
    }
    int last = optional.size() - 1;
    long previous = optional.get(last).count();
    long available = Long.MAX_VALUE - previous;
    if (amount > available) {
      optional.set(last, new RecipeCandidate.Output<>(key, Long.MAX_VALUE, false, true));
      optional.add(new RecipeCandidate.Output<>(key, amount - available, false, true));
    } else {
      optional.set(last, new RecipeCandidate.Output<>(key, previous + amount, false, true));
    }
  }

  public List<RecipeCandidate.Output<K>> claims() {
    var result = new ArrayList<RecipeCandidate.Output<K>>();
    required.forEach((key, amount) -> result.add(new RecipeCandidate.Output<>(key, amount)));
    result.addAll(optional);

    return List.copyOf(result);
  }
}
