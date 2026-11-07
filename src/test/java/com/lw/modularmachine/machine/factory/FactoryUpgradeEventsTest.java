package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.api.upgrade.FactoryUpgradeEntry;

import com.lw.modularmachine.api.upgrade.FactoryUpgradeEvents;

import com.lw.modularmachine.machine.recipe.FrozenItemRun;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FactoryUpgradeEventsTest {
  private static final ResourceLocation ID = ResourceLocation.parse("test:upgrade");
  private static final FactoryUpgradeEntry EFFECT = new FactoryUpgradeEntry(ID, 0, 1, 1, 1, 1, 1, 1);

  @Test
  void sourceOwnsItsPositionAndEveryNestedItemDataCopy() {
    var position = new BlockPos.MutableBlockPos(1, 2, 3);
    var itemData = new CompoundTag();
    var nested = new CompoundTag();
    nested.putInt("value", 4);
    itemData.put("nested", nested);
    var source = new FactoryUpgradeEvents.Upgrade(ID, ID, position, 0, 2, EFFECT, itemData);
    position.set(8, 9, 10);
    nested.putInt("value", 9);
    source.itemData().getCompound("nested").putInt("value", 7);

    assertEquals(new BlockPos(1, 2, 3), source.busPosition());
    assertEquals(4, source.itemData().getCompound("nested").getInt("value"));
    assertEquals(2, source.count());
  }

  @Test
  void contextOwnsItsRunListAndControllerPosition() {
    var position = new BlockPos.MutableBlockPos(1, 2, 3);
    var runs = new ArrayList<>(List.of(run()));
    var context = new FactoryUpgradeEvents.Context(
        FactoryUpgradeEvents.Type.STARTED,
        ID,
        ID,
        position,
        "main",
        10,
        runs);
    runs.clear();
    position.set(7, 8, 9);

    assertEquals(new BlockPos(1, 2, 3), context.controllerPosition());
    assertEquals(1, context.runs().size());
    assertThrows(UnsupportedOperationException.class, () -> context.runs().clear());
  }

  @Test
  void rejectsInvalidSlotCountsAndRunProgress() {
    for (int slot : List.of(-1, FactoryUpgradeCatalog.MAX_SLOTS)) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new FactoryUpgradeEvents.Upgrade(ID, ID, BlockPos.ZERO, slot, 1, EFFECT, new CompoundTag()));
    }
    for (int count : List.of(0, 257)) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new FactoryUpgradeEvents.Upgrade(ID, ID, BlockPos.ZERO, 0, count, EFFECT, new CompoundTag()));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> new FactoryUpgradeEvents.RunSnapshot(UUID.randomUUID(), ID, FrozenItemRun.State.RUNNING, 2, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FactoryUpgradeEvents.RunSnapshot(UUID.randomUUID(), ID, FrozenItemRun.State.OUTPUT_PENDING, 0, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FactoryUpgradeEvents.Context(
            FactoryUpgradeEvents.Type.STARTED,
            ID,
            ID,
            BlockPos.ZERO,
            "main",
            0,
            List.of()));
  }

  private static FactoryUpgradeEvents.RunSnapshot run() {
    return new FactoryUpgradeEvents.RunSnapshot(UUID.randomUUID(), ID, FrozenItemRun.State.RUNNING, 0, 20);
  }
}
