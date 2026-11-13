package com.lw.modularmachine.machine.network.pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeSnapshot;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PatternRecipeDefinitionTest {
  private static final Resource IRON = new Resource("minecraft:iron_ingot", Map.of());
  private static final Resource GOLD = new Resource("minecraft:gold_ingot", Map.of());
  private static final Resource OUTPUT = new Resource("test:product", Map.of("quality", "normal"));
  private static final Map<Resource, Long> OUTPUTS = Map.of(OUTPUT, 1L);

  @Test
  void rejectsUnderdeclaredCostsThatWouldConsumeAnotherDelivery() {
    // Two 1-iron deliveries cannot fund a single 2-iron native execution: that
    // would leave the CPU with only one of its two promised outputs.
    assertFalse(matches(Map.of(IRON, 1L), Map.of(IRON, 2L)));
    assertFalse(matches(Map.of(IRON, 3L), Map.of(IRON, 2L)));
    assertTrue(matches(Map.of(IRON, 2L), Map.of(IRON, 2L)));
  }

  @Test
  void permitsOtherNativeResourcesButPreservesDeclaredComponentIdentity() {
    assertTrue(matches(Map.of(IRON, 2L), Map.of(IRON, 2L, GOLD, 3L)));
    var renamedIron = new Resource("minecraft:iron_ingot", Map.of("custom_name", "specific iron"));
    assertFalse(matches(Map.of(renamedIron, 2L), Map.of(IRON, 2L, GOLD, 3L)));
    assertFalse(matches(Map.of(IRON, 2L, GOLD, 1L), Map.of(IRON, 2L)));
  }

  @Test
  void matchesOneByproductWithoutRequiringAllNativeOutputs() {
    var redstone = new Resource("minecraft:redstone", Map.of());
    var circuit = new Resource("test:circuit", Map.of());
    var ironBlock = new Resource("minecraft:iron_block", Map.of());
    assertTrue(
        PatternRecipeDefinition.matchesDeclaredAmounts(
            Map.of(IRON, 2L),
            Map.of(ironBlock, 1L),
            Map.of(IRON, 2L, redstone, 1L),
            Map.of(circuit, 1L, ironBlock, 1L)));
  }

  @Test
  void acceptsAnyExactAdvertisedOutput() {
    var inputs = Map.of(IRON, 2L);
    assertTrue(
        PatternRecipeDefinition
            .matchesDeclaredAmounts(inputs, Map.of(OUTPUT, 1L, GOLD, 1L), inputs, Map.of(OUTPUT, 2L, GOLD, 1L)));
    assertTrue(PatternRecipeDefinition.matchesDeclaredAmounts(inputs, OUTPUTS, inputs, Map.of(OUTPUT, 1L, GOLD, 1L)));
  }

  @Test
  void rejectsOutputsWithoutAnyMatchingQuantityAndComponents() {
    var inputs = Map.of(IRON, 2L);
    assertFalse(PatternRecipeDefinition.matchesDeclaredAmounts(inputs, OUTPUTS, inputs, Map.of(OUTPUT, 2L)));
    assertFalse(PatternRecipeDefinition.matchesDeclaredAmounts(inputs, OUTPUTS, inputs, Map.of(GOLD, 1L)));
    assertFalse(PatternRecipeDefinition.matchesDeclaredAmounts(inputs, OUTPUTS, inputs, Map.of()));
    var differentComponent = new Resource("test:product", Map.of("quality", "special"));
    assertFalse(
        PatternRecipeDefinition.matchesDeclaredAmounts(inputs, OUTPUTS, inputs, Map.of(differentComponent, 1L)));
    assertTrue(PatternRecipeDefinition.matchesDeclaredAmounts(inputs, OUTPUTS, inputs, OUTPUTS));
  }

  @Test
  void comparesFullStartupAndTickTotalsWithoutNarrowingToInt() {
    var fluid = new Resource("test:fluid", Map.of());
    long startup = 2_000_000_000L;
    long allTicks = 1_000_000_000L;
    var nativeTotal = Map.of(fluid, startup + allTicks);
    assertFalse(matches(Map.of(fluid, startup), nativeTotal));
    assertFalse(matches(Map.of(fluid, startup + allTicks + 1), nativeTotal));
    assertTrue(matches(Map.of(fluid, startup + allTicks), nativeTotal));
  }

  @Test
  void appliesUpgradeDurationAndRoundedFluidFeeBeforeMatching() {
    var upgrades = new FactoryUpgradeSnapshot(0, 1, 2, 1, 1, 1, 3, 2);
    var costs = PatternRecipeDefinition.executionCosts(7, 3, upgrades);
    assertEquals(4, costs.duration());
    assertEquals(5, costs.fluidPerTick());
    var fluid = new Resource("test:fluid", Map.of());
    var chemical = new Resource("test:chemical", Map.of());
    var nativeTotal = Map.of(fluid, 2 + costs.totalPerTick(costs.fluidPerTick()), chemical, costs.totalPerTick(7));
    assertTrue(matches(Map.of(fluid, 22L, chemical, 28L), nativeTotal));
    assertFalse(matches(Map.of(fluid, 23L, chemical, 49L), nativeTotal));
  }

  @Test
  void rejectsUpgradeCostsThatCannotBeFrozenOrSummedExactly() {
    var slower = new FactoryUpgradeSnapshot(0, 1, 1, 2, 1, 1, 1, 1);
    var costlyFluid = new FactoryUpgradeSnapshot(0, 1, 1, 1, 1, 1, 2, 1);
    assertThrows(ArithmeticException.class, () -> PatternRecipeDefinition.executionCosts(Integer.MAX_VALUE, 0, slower));
    assertThrows(
        ArithmeticException.class,
        () -> PatternRecipeDefinition.executionCosts(1, Integer.MAX_VALUE, costlyFluid));
    var costs = PatternRecipeDefinition.executionCosts(2, 0, FactoryUpgradeSnapshot.identity(0));
    assertThrows(ArithmeticException.class, () -> costs.totalPerTick(Long.MAX_VALUE));
  }

  private static boolean matches(Map<Resource, Long> patternInputs, Map<Resource, Long> nativeInputs) {
    return PatternRecipeDefinition.matchesDeclaredAmounts(patternInputs, OUTPUTS, nativeInputs, OUTPUTS);
  }

  private record Resource(String id, Map<String, String> components) {
    private Resource {
      components = Map.copyOf(components);
    }
  }
}
