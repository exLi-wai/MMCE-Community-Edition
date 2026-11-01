package com.lw.modularmachine.machine.factory;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FactoryParallelBatchTest {
  private static final ResourceLocation RECIPE = ResourceLocation.fromNamespaceAndPath("mmcece", "test");

  @Test
  void plansBoundedSameRecipeBatch() {
    var batch = FactoryParallelBatch.plan(RECIPE, 8, 3, 4, 6, 10);
    assertEquals(3, batch.instances());
    assertEquals(30, batch.workUsed());
    assertEquals(batch, FactoryParallelBatch.fromPlans(List.of(RECIPE, RECIPE, RECIPE), 8, 10));
  }

  @Test
  void rejectsMixedRecipesAndZeroCapacity() {
    assertThrows(
        IllegalArgumentException.class,
        () -> FactoryParallelBatch
            .fromPlans(List.of(RECIPE, ResourceLocation.fromNamespaceAndPath("mmcece", "other")), 8, 1));
    assertThrows(IllegalArgumentException.class, () -> FactoryParallelBatch.plan(RECIPE, 8, 0, 4, 6, 1));
  }
}
