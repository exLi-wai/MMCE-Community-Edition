package com.lw.modularmachine.presentation;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Locale;

class ControllerStatusFormatTest {
  @Test
  void progressHandlesLongRecipesAndTruncatesFractionalPercentages() {
    assertEquals("50%", ControllerStatusFormat.progressPercent(30_000_000, 60_000_000));
    assertEquals("66%", ControllerStatusFormat.progressPercent(2, 3));
  }

  @Test
  void progressHandlesMissingDurationAndClampsToValidPercentages() {
    assertEquals("0%", ControllerStatusFormat.progressPercent(10, 0));
    assertEquals("0%", ControllerStatusFormat.progressPercent(-1, 10));
    assertEquals("100%", ControllerStatusFormat.progressPercent(11, 10));
  }

  @Test
  void searchTimeDisplaysMillisecondsWithTwoDecimalPlaces() {
    assertEquals("1.23", ControllerStatusFormat.searchMilliseconds(1234));
    assertEquals("1.00", ControllerStatusFormat.searchMilliseconds(1000));
  }

  @Test
  void menuRetainsLegacyAliasesAndLowercasesOtherStatusNames() {
    String prefix = "menu.mmcece.controller.state.";
    assertEquals(prefix + "no_recipe", key(ControllerStatusFormat.menuRecipeStatus("IDLE", false)));
    assertEquals(prefix + "idle", key(ControllerStatusFormat.menuRecipeStatus("IDLE", true)));
    assertEquals(prefix + "crafting", key(ControllerStatusFormat.menuRecipeStatus("WAITING_TICK", true)));
    assertEquals(prefix + "crafting", key(ControllerStatusFormat.menuRecipeStatus("ADVANCED", true)));
    assertEquals(prefix + "missing_energy", key(ControllerStatusFormat.menuRecipeStatus("WAITING_ENERGY", true)));
    assertEquals(prefix + "missing_fluid", key(ControllerStatusFormat.menuRecipeStatus("WAITING_FLUID", true)));
    assertEquals(prefix + "new_status", key(ControllerStatusFormat.menuRecipeStatus("NEW_STATUS", false)));
  }

  @Test
  void jadeShowsMissingPortsForPaidRunsAndKeepsNormalProgressCompact() {
    String prefix = "jade.mmcece.run_status.";
    for (String resource : new String[]{"item", "fluid", "energy", "chemical"}) {
      for (String direction : new String[]{"input", "output"}) {
        String suffix = "missing_" + resource + "_" + direction;
        var detail = ControllerStatusFormat.jadeRecipeStatus(suffix.toUpperCase(Locale.ROOT), true);
        assertEquals(prefix + suffix, key(detail.orElseThrow()));
      }
    }
    assertTrue(ControllerStatusFormat.jadeRecipeStatus("WAITING_TICK", true).isEmpty());
    assertEquals(
        prefix + "waiting_tick",
        key(ControllerStatusFormat.jadeRecipeStatus("WAITING_TICK", false).orElseThrow()));
  }

  @Test
  void sharedLabelsCreateIndependentTextComponents() {
    var reason = ControllerStatusFormat.factoryThreadReason("OUTPUT_BLOCKED");
    reason.append(" first view");
    var second = ControllerStatusFormat.factoryThreadReason("OUTPUT_BLOCKED");
    assertTrue(second.getSiblings().isEmpty());
    assertEquals("menu.mmcece.factory.reason.output_blocked", key(second));
    assertEquals("menu.mmcece.factory.thread_state.running", key(ControllerStatusFormat.factoryThreadState("RUNNING")));
    assertEquals("jade.mmcece.failure.mismatch", key(ControllerStatusFormat.structureFailure("MISMATCH")));
  }

  private static String key(Component component) {
    return assertInstanceOf(TranslatableContents.class, component.getContents()).getKey();
  }
}
