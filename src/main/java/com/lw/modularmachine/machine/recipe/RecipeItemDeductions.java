package com.lw.modularmachine.machine.recipe;

import java.util.HashMap;
import java.util.Map;
import org.jetbrains.annotations.ApiStatus;

/**
 * Allocates sampled item demands against each physical slot once, with exact
 * component claims first.
 */
@ApiStatus.Internal
public final class RecipeItemDeductions {
  private final Map<ItemResourceKey, Long> remaining;
  private final boolean hasExact;
  private final boolean hasItemOnly;

  public RecipeItemDeductions(Map<ItemResourceKey, Long> sampled) {
    remaining = new HashMap<>(sampled);
    hasItemOnly = sampled.keySet().stream().anyMatch(ItemResourceKey::ignoresComponents);
    hasExact = !hasItemOnly || sampled.keySet().stream().anyMatch(key -> !key.ignoresComponents());
  }

  public long take(ItemResourceKey physical, long available) {
    long exact = hasExact ? takeRemaining(physical, available) : 0;
    if (hasItemOnly && exact < available) {
      return exact + takeRemaining(physical.ignoringComponents(), available - exact);
    }
    return exact;
  }

  private long takeRemaining(ItemResourceKey key, long available) {
    var need = remaining.get(key);
    if (need == null) {
      return 0;
    }
    if (need <= available) {
      remaining.remove(key);
      return need;
    }
    remaining.put(key, need - available);
    return available;
  }

  public boolean complete() {
    return remaining.isEmpty();
  }
}
