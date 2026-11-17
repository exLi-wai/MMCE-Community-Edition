package com.lw.modularmachine.integration.kubejs;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KubeJSUpgradeDeclarationsTest {
  @Test
  void fixedUpgradeBuilderIsCapturedInTheScriptSnapshot() {
    var registration = KubeJSUpgradeDeclarations.registration();
    registration.fixedUpgrade("minecraft:amethyst_shard", "kubejs:test_parallel").parallelAdd(2)
        .component("minecraft:custom_name", "Test upgrade").componentNumber("minecraft:damage", 4)
        .componentBoolean("minecraft:unbreakable", true);

    assertTrue(registration.valid());
    var snapshot = registration.snapshot();
    assertEquals(1, snapshot.definitions().size());
    assertEquals("kubejs:test_parallel", snapshot.definitions().getFirst().effect().id().toString());
    assertThrows(IllegalStateException.class, registration::snapshot);
  }

  @Test
  void fixedUpgradeRejectsNonKubeJsIdentifiers() {
    var registration = KubeJSUpgradeDeclarations.registration();
    registration.fixedUpgrade("minecraft:amethyst_shard", "example:test_parallel");

    assertFalse(registration.valid());
    assertTrue(registration.snapshot().definitions().isEmpty());
  }
}
