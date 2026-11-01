package com.lw.modularmachine.machine.recipe;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Shared capacity policy for start admission and paid output planning. Zero
 * entries are deliberate.
 */
record ItemSlotDomain(int capacity, String selector) {
  Map<ItemResourceKey, Long> limits(Set<ItemResourceKey> keys) {
    var result = new HashMap<ItemResourceKey, Long>();
    for (var key : keys) {
      result.put(key, selector.equals(key.selector()) ? (long) Math.min(capacity, key.maxStackSize()) : 0L);
    }

    return Map.copyOf(result);
  }
}
