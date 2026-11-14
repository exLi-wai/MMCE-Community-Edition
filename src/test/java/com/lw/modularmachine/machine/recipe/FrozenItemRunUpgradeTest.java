package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.upgrade.FactoryUpgradeSnapshot;
import com.lw.modularmachine.machine.PortKind;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FrozenItemRunUpgradeTest {
  @Test
  void freezesDurationAndBothFeesWithoutChangingTheSource() {
    var source = FrozenItemRunTest.valid();
    var definition = source.getCompound("definition");
    definition.putLong("energy_per_tick", 5);
    var fluid = new CompoundTag();
    fluid.putString("id", "minecraft:water");
    fluid.putInt("amount", 125);
    definition.put("fluid_per_tick", fluid);
    FrozenItemRunTest.addPort(source, PortKind.ENERGY_INPUT);
    FrozenItemRunTest.addPort(source, PortKind.FLUID_INPUT);
    var before = definition.copy();
    var upgrades = new FactoryUpgradeSnapshot(8, Integer.MAX_VALUE, 3, 2, 3, 2, 1, 2);
    var frozen = FrozenItemRun.applyFactoryUpgrades(definition, upgrades);
    source.put("definition", frozen);
    source.putInt("duration", frozen.getInt("duration"));
    var restored = FrozenItemRun.read(FrozenItemRun.read(source).save());

    assertEquals(before, definition);
    assertEquals(14, restored.duration());
    assertEquals(8, restored.energyPerTick());
    assertEquals(63, restored.definition().getCompound("fluid_per_tick").getInt("amount"));
    assertEquals(before.get("inputs"), restored.definition().get("inputs"));
    assertEquals(before.get("outputs"), restored.definition().get("outputs"));
  }

  @Test
  void rejectsFeeOverflowWithoutMutatingTheDefinition() {
    var definition = FrozenItemRunTest.valid().getCompound("definition");
    definition.putLong("energy_per_tick", Long.MAX_VALUE);
    var before = definition.copy();
    var upgrades = new FactoryUpgradeSnapshot(1, 1, 1, 1, 2, 1, 1, 1);

    assertThrows(ArithmeticException.class, () -> FrozenItemRun.applyFactoryUpgrades(definition, upgrades));
    assertEquals(before, definition);
    definition.remove("energy_per_tick");
    var fluid = new CompoundTag();
    fluid.putInt("amount", Integer.MAX_VALUE);
    definition.put("fluid_per_tick", fluid);
    var fluidUpgrades = new FactoryUpgradeSnapshot(1, 1, 1, 1, 1, 1, 2, 1);
    assertThrows(ArithmeticException.class, () -> FrozenItemRun.applyFactoryUpgrades(definition, fluidUpgrades));
    assertEquals(Integer.MAX_VALUE, definition.getCompound("fluid_per_tick").getInt("amount"));
  }
}
