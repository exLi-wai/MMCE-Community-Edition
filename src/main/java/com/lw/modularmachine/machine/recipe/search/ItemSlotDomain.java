package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.machine.recipe.ItemResourceKey;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.ApiStatus;

/**
 * Shared capacity policy for start admission and paid output planning. Zero
 * entries are deliberate.
 */
@ApiStatus.Internal
public record ItemSlotDomain(int capacity, String selector, boolean bulk) {
  public int capacityFor(ItemResourceKey key) {
    return bulk ? capacity : Math.min(capacity, key.maxStackSize());
  }

  public Map<ItemResourceKey, Long> limits(Set<ItemResourceKey> keys) {
    var result = new HashMap<ItemResourceKey, Long>();
    for (var key : keys) {
      result.put(key, selector.equals(key.selector()) ? (long) capacityFor(key) : 0L);
    }

    return Map.copyOf(result);
  }
}
