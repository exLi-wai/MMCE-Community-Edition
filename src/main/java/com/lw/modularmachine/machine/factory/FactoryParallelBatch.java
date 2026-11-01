package com.lw.modularmachine.machine.factory;

import java.util.List;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable resource-admission result for a same-recipe parallel start batch.
 */
public record FactoryParallelBatch(ResourceLocation recipeId, int instances, long workUsed) {
  public FactoryParallelBatch {
    Objects.requireNonNull(recipeId, "recipeId");
    if (instances < 1 || workUsed < 0) {
      throw new IllegalArgumentException("Invalid factory parallel batch");
    }
  }

  public static FactoryParallelBatch plan(ResourceLocation recipeId, int parallelLimit, int inputCapacity, int outputCapacity, int budget, long workPerInstance) {
    if (workPerInstance < 0) {
      throw new IllegalArgumentException("Negative parallel planning work");
    }
    int instances = FactoryParallelism.effectiveInstances(parallelLimit, inputCapacity, outputCapacity, budget);
    if (instances == 0) {
      throw new IllegalArgumentException("No factory parallel instance can start");
    }
    return new FactoryParallelBatch(recipeId, instances, Math.multiplyExact(workPerInstance, instances));
  }

  public static FactoryParallelBatch fromPlans(List<? extends ResourceLocation> recipeIds, int parallelLimit, long workPerInstance) {
    Objects.requireNonNull(recipeIds, "recipeIds");
    if (recipeIds.isEmpty() || recipeIds.size() > parallelLimit || workPerInstance < 0) {
      throw new IllegalArgumentException("Invalid factory parallel plans");
    }
    var recipe = Objects.requireNonNull(recipeIds.get(0), "recipe id");
    for (var id : recipeIds) {
      if (!recipe.equals(Objects.requireNonNull(id, "recipe id"))) {
        throw new IllegalArgumentException("Parallel plans must use one recipe");
      }
    }
    return new FactoryParallelBatch(recipe, recipeIds.size(), Math.multiplyExact(workPerInstance, recipeIds.size()));
  }
}
