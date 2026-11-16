package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.upgrade.UpgradeSnapshot;
import com.lw.modularmachine.machine.PortKind;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FrozenRecipeRunUpgradeTest {
  @Test
  void freezesDurationAndBothFeesWithoutChangingTheSource() {
    var source = FrozenRecipeRunTest.valid();
    var definition = source.getCompound("definition");
    definition.putLong("energy_per_tick", 5);
    var fluid = new CompoundTag();
    fluid.putString("id", "minecraft:water");
    fluid.putInt("amount", 125);
    definition.put("fluid_per_tick", fluid);
    FrozenRecipeRunTest.addPort(source, PortKind.ENERGY_INPUT);
    FrozenRecipeRunTest.addPort(source, PortKind.FLUID_INPUT);
    var before = definition.copy();
    var upgrades = new UpgradeSnapshot(8, Integer.MAX_VALUE, 3, 2, 3, 2, 1, 2);
    var frozen = FrozenRecipeFactory.applyUpgrades(definition, upgrades);
    source.put("definition", frozen);
    source.putInt("duration", frozen.getInt("duration"));
    var restored = FrozenRecipeRun.read(FrozenRecipeRun.read(source).save());

    assertEquals(before, definition);
    assertEquals(14, restored.duration());
    assertEquals(8, restored.energyPerTick());
    assertEquals(63, restored.definition().getCompound("fluid_per_tick").getInt("amount"));
    assertEquals(before.get("inputs"), restored.definition().get("inputs"));
    assertEquals(before.get("outputs"), restored.definition().get("outputs"));
  }

  @Test
  void rejectsFeeOverflowWithoutMutatingTheDefinition() {
    var definition = FrozenRecipeRunTest.valid().getCompound("definition");
    definition.putLong("energy_per_tick", Long.MAX_VALUE);
    var before = definition.copy();
    var upgrades = new UpgradeSnapshot(1, 1, 1, 1, 2, 1, 1, 1);

    assertThrows(ArithmeticException.class, () -> FrozenRecipeFactory.applyUpgrades(definition, upgrades));
    assertEquals(before, definition);
    definition.remove("energy_per_tick");
    var fluid = new CompoundTag();
    fluid.putInt("amount", Integer.MAX_VALUE);
    definition.put("fluid_per_tick", fluid);
    var fluidUpgrades = new UpgradeSnapshot(1, 1, 1, 1, 1, 1, 2, 1);
    assertThrows(ArithmeticException.class, () -> FrozenRecipeFactory.applyUpgrades(definition, fluidUpgrades));
    assertEquals(Integer.MAX_VALUE, definition.getCompound("fluid_per_tick").getInt("amount"));
  }

  @Test
  void aggregateAdmissionCapsTheUpgradedFluidFeeAtItsActualFrozenBoundary() {
    var upgrades = new UpgradeSnapshot(1, 2048, 1, 1, 1, 1, 2, 1);
    int admitted = FrozenRecipeFactory.aggregateTickLimit(2048, 0, 0, 600_000, upgrades);
    var source = tickDefinition(0, 0, 600_000);
    var frozen = FrozenRecipeFactory.applyUpgrades(source, upgrades);
    var oversized = frozen.copy();

    assertEquals(1789, admitted);
    FrozenRecipeFactory.scaleDefinition(frozen, admitted);
    assertEquals(2_146_800_000, frozen.getCompound("fluid_per_tick").getInt("amount"));
    assertThrows(ArithmeticException.class, () -> FrozenRecipeFactory.scaleDefinition(oversized, admitted + 1));
    assertEquals(600_000, source.getCompound("fluid_per_tick").getInt("amount"));
  }

  @Test
  void aggregateAdmissionRoundsTheIndividualFeeBeforeMultiplyingTheBatch() {
    var upgrades = new UpgradeSnapshot(1, 2048, 1, 1, 1, 1, 1, 2);
    int admitted = FrozenRecipeFactory.aggregateTickLimit(2048, 0, 0, Integer.MAX_VALUE, upgrades);
    var frozen = FrozenRecipeFactory.applyUpgrades(tickDefinition(0, 0, Integer.MAX_VALUE), upgrades);

    assertEquals(1, admitted);
    assertEquals(1_073_741_824, frozen.getCompound("fluid_per_tick").getInt("amount"));
    assertThrows(ArithmeticException.class, () -> FrozenRecipeFactory.scaleDefinition(frozen, admitted + 1));
    assertEquals(2147, FrozenRecipeFactory.aggregateTickLimit(4096, 0, 0, 2_000_000, upgrades));
  }

  @Test
  void aggregateAdmissionUsesTheRoundedUpgradedEnergyCost() {
    long base = Long.MAX_VALUE / 4;
    var upgrades = new UpgradeSnapshot(1, 2048, 1, 1, 3, 2, 1, 1);
    int admitted = FrozenRecipeFactory.aggregateTickLimit(2048, base, 0, 0, upgrades);
    var frozen = FrozenRecipeFactory.applyUpgrades(tickDefinition(base, 0, 0), upgrades);
    var oversized = frozen.copy();

    assertEquals(2, admitted);
    FrozenRecipeFactory.scaleDefinition(frozen, admitted);
    assertEquals(6_917_529_027_641_081_854L, frozen.getLong("energy_per_tick"));
    assertThrows(ArithmeticException.class, () -> FrozenRecipeFactory.scaleDefinition(oversized, admitted + 1));
    var cheaper = new UpgradeSnapshot(1, 2048, 1, 1, 1, 2, 1, 1);
    assertEquals(7, FrozenRecipeFactory.aggregateTickLimit(2048, base, 0, 0, cheaper));
  }

  @Test
  void aggregateAdmissionDoesNotApplyTheInputUpgradeToEnergyProduction() {
    long output = Long.MAX_VALUE / 3;
    var upgrades = new UpgradeSnapshot(1, 2048, 1, 1, 2, 1, 1, 1);
    int admitted = FrozenRecipeFactory.aggregateTickLimit(2048, 0, output, 0, upgrades);
    var frozen = FrozenRecipeFactory.applyUpgrades(tickDefinition(0, output, 0), upgrades);
    var oversized = frozen.copy();

    assertEquals(3, admitted);
    assertEquals(output, frozen.getLong("energy_output_per_tick"));
    FrozenRecipeFactory.scaleDefinition(frozen, admitted);
    assertEquals(Long.MAX_VALUE - 1, frozen.getLong("energy_output_per_tick"));
    assertThrows(ArithmeticException.class, () -> FrozenRecipeFactory.scaleDefinition(oversized, admitted + 1));
  }

  @Test
  void aggregateAdmissionRejectsAnUnrepresentableSingleUpgradedRun() {
    var upgrades = new UpgradeSnapshot(1, 2048, 1, 1, 2, 1, 2, 1);

    assertEquals(0, FrozenRecipeFactory.aggregateTickLimit(2048, Long.MAX_VALUE, 0, 0, upgrades));
    assertEquals(0, FrozenRecipeFactory.aggregateTickLimit(2048, 0, 0, Integer.MAX_VALUE, upgrades));
    assertThrows(
        ArithmeticException.class,
        () -> FrozenRecipeFactory.applyUpgrades(tickDefinition(Long.MAX_VALUE, 0, 0), upgrades));
    assertThrows(
        ArithmeticException.class,
        () -> FrozenRecipeFactory.applyUpgrades(tickDefinition(0, 0, Integer.MAX_VALUE), upgrades));
  }

  private static CompoundTag tickDefinition(long energyInput, long energyOutput, int fluidInput) {
    var definition = FrozenRecipeRunTest.valid().getCompound("definition");
    if (energyInput > 0) {
      definition.putLong("energy_per_tick", energyInput);
    }
    if (energyOutput > 0) {
      definition.putLong("energy_output_per_tick", energyOutput);
    }
    if (fluidInput > 0) {
      var fluid = new CompoundTag();
      fluid.putString("id", "minecraft:water");
      fluid.putInt("amount", fluidInput);
      definition.put("fluid_per_tick", fluid);
    }

    return definition;
  }
}
