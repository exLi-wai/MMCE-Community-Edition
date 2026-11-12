package com.lw.modularmachine.machine.factory;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactoryUpgradeKubeJSHandlersTest {
  @Test
  void fixedUpgradeBuilderIsCapturedInTheScriptSnapshot() {
    var registration = FactoryUpgradeKubeJSHandlers.registration();
    registration.fixedUpgrade("kubejs:test_parallel", "minecraft:amethyst_shard").parallelAdd(2)
        .component("minecraft:custom_name", "Test upgrade").componentNumber("minecraft:damage", 4)
        .componentBoolean("minecraft:unbreakable", true);

    assertTrue(registration.valid());
    assertEquals(1, registration.snapshot().definitions().size());
    assertEquals("kubejs:test_parallel", registration.snapshot().definitions().getFirst().effect().id().toString());
  }

  @Test
  void fixedUpgradeRejectsNonKubeJsIdentifiers() {
    var registration = FactoryUpgradeKubeJSHandlers.registration();
    registration.fixedUpgrade("example:test_parallel", "minecraft:amethyst_shard");

    assertFalse(registration.valid());
    assertTrue(registration.snapshot().definitions().isEmpty());
  }
}
