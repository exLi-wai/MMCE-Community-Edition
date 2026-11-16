package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.upgrade.UpgradeSnapshot;
import com.lw.modularmachine.machine.PortKind;
import java.util.Collections;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactoryParallelEnergyAdmissionTest {
  @Test
  void upgradedFactoryCostCapsTheBatchBeforeTheThirdRunCanOverflow() {
    long base = Long.MAX_VALUE / 4;
    var upgrades = new UpgradeSnapshot(1, 2048, 1, 1, 2, 1, 1, 1);
    int admitted = FrozenRecipeFactory.factoryTickLimit(2048, base, 0, upgrades);
    var frozen = energyRun(upgrades.energyPerTick(base), 0, "");

    assertEquals(2, admitted);
    assertTrue(FactoryParallelStarter.energyTotalsFit(Collections.nCopies(admitted, frozen)));
    assertFalse(FactoryParallelStarter.energyTotalsFit(Collections.nCopies(admitted + 1, frozen)));
  }

  @Test
  void factoryAdmissionRoundsEachFeeBeforeSummingAndKeepsIntParallelLimits() {
    long base = Long.MAX_VALUE / 4;
    var cheaper = new UpgradeSnapshot(1, 2048, 1, 1, 1, 2, 1, 1);
    int admitted = FrozenRecipeFactory.factoryTickLimit(2048, base, 0, cheaper);
    var frozen = energyRun(cheaper.energyPerTick(base), 0, "");

    assertEquals(7, admitted);
    assertTrue(FactoryParallelStarter.energyTotalsFit(Collections.nCopies(admitted, frozen)));
    assertFalse(FactoryParallelStarter.energyTotalsFit(Collections.nCopies(admitted + 1, frozen)));
    assertEquals(
        Integer.MAX_VALUE,
        FrozenRecipeFactory.factoryTickLimit(Integer.MAX_VALUE, 1, 1, UpgradeSnapshot.identity(0)));
  }

  @Test
  void factoryEnergyProductionUsesItsOwnLimitWithoutTheConsumptionMultiplier() {
    long output = Long.MAX_VALUE / 3;
    var upgrades = new UpgradeSnapshot(1, 2048, 1, 1, 2, 1, 1, 1);
    int admitted = FrozenRecipeFactory.factoryTickLimit(2048, 1, output, upgrades);
    var frozen = energyRun(2, output, "");

    assertEquals(3, admitted);
    assertTrue(FactoryParallelStarter.energyTotalsFit(Collections.nCopies(admitted, frozen)));
    assertFalse(FactoryParallelStarter.energyTotalsFit(Collections.nCopies(admitted + 1, frozen)));
  }

  @Test
  void factoryAdmissionRejectsAnUnrepresentableSingleUpgradedFee() {
    var upgrades = new UpgradeSnapshot(1, 2048, 1, 1, 2, 1, 1, 1);

    assertEquals(0, FrozenRecipeFactory.factoryTickLimit(2048, Long.MAX_VALUE, 0, upgrades));
  }

  @Test
  void finalCheckSeparatesSelectorsAndDirectionsButChecksEveryFrozenRun() {
    var first = energyRun(Long.MAX_VALUE, Long.MAX_VALUE, "first");
    var second = energyRun(Long.MAX_VALUE, Long.MAX_VALUE, "second");

    assertTrue(FactoryParallelStarter.energyTotalsFit(List.of(first, second)));
    assertFalse(FactoryParallelStarter.energyTotalsFit(List.of(first, energyRun(1, 0, "first"))));
    assertFalse(FactoryParallelStarter.energyTotalsFit(List.of(first, energyRun(0, 1, "first"))));
  }

  private static FrozenRecipeRun energyRun(long input, long output, String selector) {
    var source = FrozenRecipeRunTest.valid();
    var definition = source.getCompound("definition");
    var selectors = new CompoundTag();
    if (input > 0) {
      definition.putLong("energy_per_tick", input);
      selectors.putString("energy_input", selector);
      addEnergyPort(source, PortKind.ENERGY_INPUT, selector);
    }
    if (output > 0) {
      definition.putLong("energy_output_per_tick", output);
      source.putInt("energy_output_progress", 0);
      selectors.putString("energy_output", selector);
      addEnergyPort(source, PortKind.ENERGY_OUTPUT, selector);
    }
    definition.put("selectors", selectors);

    return FrozenRecipeRun.read(source);
  }

  private static void addEnergyPort(CompoundTag source, PortKind kind, String selector) {
    FrozenRecipeRunTest.addPort(source, kind);
    var ports = source.getList("ports", Tag.TAG_COMPOUND);
    ports.getCompound(ports.size() - 1).putString("selector", selector);
  }
}
