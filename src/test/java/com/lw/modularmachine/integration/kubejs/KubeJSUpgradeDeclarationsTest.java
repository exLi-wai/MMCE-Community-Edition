package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.api.upgrade.UpgradeRecipeChecks;
import com.lw.modularmachine.machine.upgrade.UpgradeScriptHandlers;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KubeJSUpgradeDeclarationsTest {
  @Test
  void nullCallbackIdInvalidatesAllDeclarations() {
    var registration = KubeJSUpgradeDeclarations.registration();
    registration.recipeCheck("kubejs:test", (context, upgrade) -> UpgradeRecipeChecks.Decision.ALLOW);
    registration.recipeCheck(null, (context, upgrade) -> UpgradeRecipeChecks.Decision.REJECT);

    assertFalse(registration.valid());
    assertEquals(UpgradeScriptHandlers.Snapshot.EMPTY, registration.snapshot());
  }

  @Test
  void duplicateCallbackInOneKindInvalidatesAllDeclarations() {
    var registration = KubeJSUpgradeDeclarations.registration();
    registration.recipeCheck("kubejs:test", (context, upgrade) -> UpgradeRecipeChecks.Decision.ALLOW);
    registration.recipeCheck("kubejs:test", (context, upgrade) -> UpgradeRecipeChecks.Decision.REJECT);

    assertFalse(registration.valid());
    assertEquals(UpgradeScriptHandlers.Snapshot.EMPTY, registration.snapshot());
  }

  @Test
  void differentKindsCanShareOneCallbackId() {
    var registration = KubeJSUpgradeDeclarations.registration();
    var id = ResourceLocation.parse("kubejs:shared");
    registration.recipeCheck(id.toString(), (context, upgrade) -> UpgradeRecipeChecks.Decision.ALLOW);
    registration.lifecycle(id.toString(), (context, upgrade) -> {
    });

    assertTrue(registration.valid());
    var snapshot = registration.snapshot();
    assertTrue(snapshot.checks().containsKey(id));
    assertTrue(snapshot.events().containsKey(id));
  }

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
