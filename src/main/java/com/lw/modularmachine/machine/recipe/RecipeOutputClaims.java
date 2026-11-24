package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.recipe.search.RecipeCandidate;
import com.lw.modularmachine.machine.recipe.search.CompiledRecipeSet;
import com.google.common.collect.Iterators;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Set;
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
  private final LinkedList<RecipeCandidate.Output<K>> optional = new LinkedList<>();
  private final Set<K> keys = new HashSet<>();
  private FrozenRows<K> frozen;

  public void add(K key, long amount, boolean ignored) {
    if (frozen != null) {
      throw new IllegalStateException("Output claims already frozen");
    }
    if (amount <= 0) {
      throw new IllegalArgumentException("Output claims require a positive quantity");
    }
    keys.add(key);
    if (!ignored) {
      required.merge(key, amount, Math::addExact);
      return;
    }
    if (optional.isEmpty() || !optional.getLast().key().equals(key)) {
      optional.add(new RecipeCandidate.Output<>(key, amount, false, true));
      return;
    }
    long previous = optional.removeLast().count();
    long available = Long.MAX_VALUE - previous;
    if (amount > available) {
      optional.addLast(new RecipeCandidate.Output<>(key, Long.MAX_VALUE, false, true));
      optional.add(new RecipeCandidate.Output<>(key, amount - available, false, true));
    } else {
      optional.addLast(new RecipeCandidate.Output<>(key, previous + amount, false, true));
    }
  }

  public List<RecipeCandidate.Output<K>> claims() {
    var result = new ArrayList<RecipeCandidate.Output<K>>();
    required.forEach((key, amount) -> result.add(new RecipeCandidate.Output<>(key, amount)));
    result.addAll(optional);

    return List.copyOf(result);
  }

  /** Transfers the accumulated rows to a reusable paid-output declaration. */
  public FrozenRows<K> freeze() {
    if (frozen == null) {
      if (keys.size() > CompiledRecipeSet.MAX_DISTINCT_OUTPUTS) {
        throw new IllegalArgumentException("Too many distinct output keys");
      }
      frozen = new FrozenRows<>(this);
    }
    return frozen;
  }

  public static final class FrozenRows<K> extends AbstractList<RecipeCandidate.Output<K>> {
    private final List<RecipeCandidate.Output<K>> requiredRows;
    private final Map<K, Long> required;
    private final List<RecipeCandidate.Output<K>> overflow;
    private final Set<K> keys;

    private FrozenRows(RecipeOutputClaims<K> source) {
      required = Collections.unmodifiableMap(source.required);
      requiredRows = required.entrySet().stream().map(row -> new RecipeCandidate.Output<>(row.getKey(), row.getValue()))
          .toList();
      overflow = Collections.unmodifiableList(source.optional);
      keys = Collections.unmodifiableSet(source.keys);
    }

    public Map<K, Long> required() {
      return required;
    }
    public List<RecipeCandidate.Output<K>> overflow() {
      return overflow;
    }
    public Set<K> keys() {
      return keys;
    }

    @Override
    public int size() {
      return Math.addExact(requiredRows.size(), overflow.size());
    }

    @Override
    public RecipeCandidate.Output<K> get(int index) {
      return index < requiredRows.size() ? requiredRows.get(index) : overflow.get(index - requiredRows.size());
    }

    @Override
    public Iterator<RecipeCandidate.Output<K>> iterator() {
      return Iterators.concat(requiredRows.iterator(), overflow.iterator());
    }
  }
}
