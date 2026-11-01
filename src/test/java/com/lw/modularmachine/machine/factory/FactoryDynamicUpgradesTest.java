package com.lw.modularmachine.machine.factory;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FactoryDynamicUpgradesTest {
  private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("test", "dynamic");

  @Test
  void contextOwnsNestedItemDataAndPositionWithoutExposingAWorldObject() {
    var position = new BlockPos.MutableBlockPos(1, 2, 3);
    var data = new CompoundTag();
    var nested = new CompoundTag();
    nested.putInt("charge", 7);
    data.put("components", nested);
    var base = new FactoryUpgradeEntry(ID, 4, 1, 1, 1, 1, 1, 1);
    var context = new FactoryDynamicUpgrades.Context(ID, ID, null, position, 0, 2, 5, base, data);
    position.set(9, 9, 9);
    nested.putInt("charge", 99);
    context.itemData().getCompound("components").putInt("charge", 1);

    assertEquals(new BlockPos(1, 2, 3), context.busPosition());
    assertEquals(7, context.itemData().getCompound("components").getInt("charge"));
    assertEquals(4, context.baseEffect().parallelAdd());
    assertEquals(2, context.count());
    assertEquals(5, context.epoch());
  }

  @Test
  void contextRejectsInvalidSlotsCountsAndEpochs() {
    var base = new FactoryUpgradeEntry(ID, 0, 1, 1, 1, 1, 1, 1);
    assertThrows(
        IllegalArgumentException.class,
        () -> new FactoryDynamicUpgrades.Context(ID, ID, null, BlockPos.ZERO, -1, 1, 0, base, new CompoundTag()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FactoryDynamicUpgrades.Context(ID, ID, null, BlockPos.ZERO, 0, 0, 0, base, new CompoundTag()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FactoryDynamicUpgrades.Context(ID, ID, null, BlockPos.ZERO, 0, 1, -1, base, new CompoundTag()));
  }
}
