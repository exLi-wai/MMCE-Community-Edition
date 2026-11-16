package com.lw.modularmachine.machine.upgrade;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpgradeCompatibilityTest {
  private static final ResourceLocation A = ResourceLocation.fromNamespaceAndPath("test", "a");
  private static final ResourceLocation B = ResourceLocation.fromNamespaceAndPath("test", "b");

  @Test
  void unrestrictedEffectsKeepTheExistingDefault() {
    var policy = UpgradeCompatibility.unrestricted();
    assertTrue(policy.allows(A));
    assertTrue(policy.allows(B));
    assertTrue(policy.allows(null));
  }

  @Test
  void whitelistRequiresAnExplicitlyAllowedMachine() {
    var policy = new UpgradeCompatibility(Set.of(A), Set.of());
    assertTrue(policy.allows(A));
    assertFalse(policy.allows(B));
    assertFalse(policy.allows(null));
  }

  @Test
  void blacklistAllowsOtherMachinesButRequiresAContext() {
    var policy = new UpgradeCompatibility(Set.of(), Set.of(B));
    assertTrue(policy.allows(A));
    assertFalse(policy.allows(B));
    assertFalse(policy.allows(null));
  }

  @Test
  void nonemptyWhitelistStillRestrictsMachinesWhenABlacklistIsPresent() {
    var policy = new UpgradeCompatibility(Set.of(A), Set.of(B));
    assertTrue(policy.allows(A));
    assertFalse(policy.allows(B));
    assertFalse(policy.allows(ResourceLocation.fromNamespaceAndPath("test", "unlisted")));
    assertThrows(IllegalArgumentException.class, () -> new UpgradeCompatibility(Set.of(A), Set.of(A)));
  }

  @Test
  void policiesOwnTheirSetsAndRejectOversizedLists() {
    var mutable = new HashSet<>(Set.of(A));
    var policy = new UpgradeCompatibility(mutable, Set.of());
    mutable.clear();
    assertTrue(policy.allows(A));
    assertThrows(UnsupportedOperationException.class, () -> policy.compatibleMachines().clear());
    for (int index = 0; index <= UpgradeCompatibility.MAX_MACHINE_IDS; index++) {
      mutable.add(ResourceLocation.fromNamespaceAndPath("test", "machine_" + index));
    }
    assertThrows(IllegalArgumentException.class, () -> new UpgradeCompatibility(mutable, Set.of()));
  }
}
