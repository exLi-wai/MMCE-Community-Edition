package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.recipe.ItemPaymentData;
import com.mojang.serialization.JsonOps;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecipeItemPaymentsTest {
  @Test
  void toolOperationsKeepDamageAndBreakExactlyAtTheBoundary() {
    assertEquals(36, RecipeItemPayments.toolCapacity(3, 100, 90, 6));
    assertEquals(new RecipeItemPayments.ToolDamage(3, 96), RecipeItemPayments.toolDamage(3, 100, 90, 1, 6));
    assertEquals(new RecipeItemPayments.ToolDamage(2, 0), RecipeItemPayments.toolDamage(3, 100, 90, 2, 6));
    assertEquals(new RecipeItemPayments.ToolDamage(1, 0), RecipeItemPayments.toolDamage(3, 100, 90, 19, 6));
    assertEquals(new RecipeItemPayments.ToolDamage(0, 0), RecipeItemPayments.toolDamage(3, 100, 90, 36, 6));
    assertThrows(IllegalArgumentException.class, () -> RecipeItemPayments.toolDamage(3, 100, 90, 37, 6));
  }

  @Test
  void hugeParallelToolOperationsUseExactLongArithmetic() {
    long operations = Math.multiplyExact((long) Integer.MAX_VALUE, Integer.MAX_VALUE);
    assertEquals(operations, RecipeItemPayments.toolCapacity(Integer.MAX_VALUE, Integer.MAX_VALUE, 0, 1));
    assertEquals(
        new RecipeItemPayments.ToolDamage(0, 0),
        RecipeItemPayments.toolDamage(Integer.MAX_VALUE, Integer.MAX_VALUE, 0, operations, 1));
    assertEquals(
        new RecipeItemPayments.ToolDamage(1, Integer.MAX_VALUE - 1),
        RecipeItemPayments.toolDamage(Integer.MAX_VALUE, Integer.MAX_VALUE, 0, operations - 1, 1));
  }

  @Test
  void independentFuelPaymentsDiscardEachRunsExcessHeat() {
    var independent = new RecipeItemPayments.FuelDemand(1000, 3);
    assertEquals(1, independent.consume(1, 1600));
    assertFalse(independent.paid());
    assertEquals(2, independent.consume(8, 1600));
    assertTrue(independent.paid());

    var aggregate = new RecipeItemPayments.FuelDemand(3000, 1);
    assertEquals(2, aggregate.consume(8, 1600));
    assertTrue(aggregate.paid());
  }

  @Test
  void independentFuelPaymentsCarryOnlyUnpaidDemandAcrossMixedSlots() {
    var demand = new RecipeItemPayments.FuelDemand(1000, 3);
    assertEquals(1, demand.consume(1, 600));
    assertEquals(2, demand.consume(2, 1600));
    assertFalse(demand.paid());
    assertEquals(2, demand.consume(8, 600));
    assertTrue(demand.paid());
    assertEquals(0, demand.consume(1, 1600));
  }

  @Test
  void hugeIndependentFuelBatchDoesNotShareHeatOrOverflow() {
    var demand = new RecipeItemPayments.FuelDemand(1, Integer.MAX_VALUE);
    assertEquals(Integer.MAX_VALUE, demand.consume(Integer.MAX_VALUE, Integer.MAX_VALUE));
    assertTrue(demand.paid());

    var exactLong = new RecipeItemPayments.FuelDemand(Long.MAX_VALUE, 1);
    assertEquals(Integer.MAX_VALUE, exactLong.consume(Integer.MAX_VALUE, Integer.MAX_VALUE));
    assertFalse(exactLong.paid());
    assertEquals(Integer.MAX_VALUE, exactLong.consume(Integer.MAX_VALUE, Integer.MAX_VALUE));
    assertFalse(exactLong.paid());
    assertEquals(5, exactLong.consume(5, Integer.MAX_VALUE));
    assertTrue(exactLong.paid());
  }

  @Test
  void fuelMetadataRoundTripsLongBurnTimeAndIndependentRules() {
    var data = new ItemPaymentData(
        List.of(new ItemPaymentData.Tool(2, 9)),
        List.of(new ItemPaymentData.Fuel(9007199254740993L, 0.5, true, "fuel")));
    var encoded = ItemPaymentData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
    assertEquals(data, ItemPaymentData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    assertTrue(data.isTool(2));
    assertFalse(data.isTool(1));
  }

  @Test
  void malformedPaymentStateCannotCreateFreeToolsOrFuel() {
    assertThrows(IllegalArgumentException.class, () -> new ItemPaymentData.Tool(0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ItemPaymentData(List.of(new ItemPaymentData.Tool(0, 1), new ItemPaymentData.Tool(0, 2)), List.of()));
    assertThrows(IllegalArgumentException.class, () -> new ItemPaymentData.Fuel(0, 1, false, ""));
    assertThrows(IllegalArgumentException.class, () -> new ItemPaymentData.Fuel(1, Double.NaN, false, ""));
    assertThrows(IllegalArgumentException.class, () -> RecipeItemPayments.toolCapacity(1, 100, 100, 1));
  }
}
