package com.lw.modularmachine.machine.network.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class PatternProviderStateTest {
  @Test
  void restoreRetainsStorageIdentityButRevokesOldLiveLeaseAuthority() {
    var state = new PatternProviderState(() -> {
    }, () -> {
    });
    var restored = PatternProviderState.load(state.save(), null, () -> {
    }, () -> {
    });
    assertTrue(restored.valid());
    assertEquals(state.providerId(), restored.providerId());
    assertNotEquals(state.instanceId(), restored.instanceId());
    assertThrows(IllegalStateException.class, () -> restored.acquire(state.instanceId(), state.revision()));
    try (var lease = restored.acquire(restored.instanceId(), restored.revision())) {
      assertThrows(IllegalStateException.class, restored::save);
      assertThrows(IllegalStateException.class, () -> restored.acquire(restored.instanceId(), 0));
      assertTrue(lease.setPattern(0, 0, null));
      assertFalse(lease.modified());
      lease.resolve(true);
    }
    assertFalse(restored.inTransaction());
  }

  @Test
  void malformedFragmentsRemainIntactAndCannotBecomeAnEmptyUsableCache() {
    var state = new PatternProviderState(() -> {
    }, () -> {
    });
    var damaged = (CompoundTag) state.save();
    damaged.remove("provider");
    var restored = PatternProviderState.load(damaged, null, () -> {
    }, () -> {
    });
    assertFalse(restored.valid());
    assertEquals(damaged, restored.save());
    assertTrue(restored.containers().isEmpty());
    assertThrows(IllegalStateException.class, () -> restored.acquire(restored.instanceId(), restored.revision()));
    var future = new CompoundTag();
    future.putInt("schema", 2);
    future.putString("unknown_cache", "do not discard");
    assertEquals(future, PatternProviderState.load(future, null, () -> {
    }, () -> {
    }).save());
  }
}
