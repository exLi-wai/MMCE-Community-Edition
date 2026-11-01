package com.lw.modularmachine.network;

import com.lw.modularmachine.machine.factory.FactoryUpgradeCatalog;
import com.lw.modularmachine.machine.factory.FactoryUpgradeEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UpgradeBusStatusPayloadTest {
  private static FactoryUpgradeEntry effect() {
    return new FactoryUpgradeEntry(ResourceLocation.fromNamespaceAndPath("test", "upgrade"), 2, 3, 2, 1, 2, 1, 1);
  }

  @Test
  void snapshotOwnsImmutableEntryAndPositionViews() {
    var position = new BlockPos.MutableBlockPos(1, 2, 3);
    var entries = new ArrayList<>(List.of(effect()));
    var status = new UpgradeBusPayloads.Status(7, position, Long.MAX_VALUE, true, entries);
    position.set(4, 5, 6);
    entries.clear();

    assertEquals(new BlockPos(1, 2, 3), status.position());
    assertEquals(1, status.entries().size());
    assertEquals(Long.MAX_VALUE, status.revision());
    assertThrows(UnsupportedOperationException.class, () -> status.entries().clear());
  }

  @Test
  void ownsMachineDiagnosticsAndBoundsTheirCombinedSlotCount() {
    var machine = ResourceLocation.fromNamespaceAndPath("pack", "machine");
    var rejected = new ArrayList<>(List.of(effect().id()));
    var status = new UpgradeBusPayloads.Status(1, BlockPos.ZERO, 2, true, List.of(), machine, rejected);
    rejected.clear();
    assertEquals(machine, status.machine());
    assertEquals(List.of(effect().id()), status.incompatible());
    assertThrows(UnsupportedOperationException.class, () -> status.incompatible().clear());
    assertThrows(
        IllegalArgumentException.class,
        () -> new UpgradeBusPayloads.Status(
            1,
            BlockPos.ZERO,
            2,
            true,
            List.of(effect()),
            machine,
            Collections.nCopies(FactoryUpgradeCatalog.MAX_SLOTS, effect().id())));
    assertThrows(
        IllegalArgumentException.class,
        () -> new UpgradeBusPayloads.Status(1, BlockPos.ZERO, 2, false, List.of(), machine, List.of(effect().id())));
  }

  @Test
  void rejectsOversizedOrInconsistentSnapshots() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new UpgradeBusPayloads.Status(
            1,
            BlockPos.ZERO,
            0,
            true,
            Collections.nCopies(FactoryUpgradeCatalog.MAX_SLOTS + 1, effect())));
    assertThrows(
        IllegalArgumentException.class,
        () -> new UpgradeBusPayloads.Status(1, BlockPos.ZERO, 0, false, List.of(effect())));
    assertThrows(
        IllegalArgumentException.class,
        () -> new UpgradeBusPayloads.Status(-1, BlockPos.ZERO, 0, true, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new UpgradeBusPayloads.Status(1, BlockPos.ZERO, -1, true, List.of()));
  }
}
