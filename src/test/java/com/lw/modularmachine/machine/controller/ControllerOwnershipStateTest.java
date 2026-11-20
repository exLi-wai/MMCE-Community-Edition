package com.lw.modularmachine.machine.controller;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ControllerOwnershipStateTest {
  @Test
  void onlyAValidUnownedControllerCanBeClaimed() {
    var first = UUID.randomUUID();
    var second = UUID.randomUUID();
    var claimed = ControllerOwnershipState.empty().claimIfUnowned(first);
    assertEquals(first, claimed.ownerId().orElseThrow());
    assertSame(claimed, claimed.claimIfUnowned(second));

    var stored = new CompoundTag();
    stored.putString("owner", "unsupported");
    var malformed = ControllerOwnershipState.load(stored);
    assertSame(malformed, malformed.claimIfUnowned(first));
    var saved = new CompoundTag();
    malformed.save(saved);
    assertEquals(stored, saved);
  }
}
