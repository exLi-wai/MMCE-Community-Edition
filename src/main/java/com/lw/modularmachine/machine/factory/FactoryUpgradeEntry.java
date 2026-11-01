package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.machine.recipe.RecipeModifier;
import com.lw.modularmachine.machine.recipe.RecipeModifiers;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Immutable data description for one factory upgrade item/effect. */
public record FactoryUpgradeEntry(
    ResourceLocation id,
    int parallelAdd,
    int speedNumerator,
    int speedDenominator,
    int energyNumerator,
    int energyDenominator,
    int fluidNumerator,
    int fluidDenominator,
    List<RecipeModifier> recipeModifiers) {
  public static final int MAX_RATIO_COMPONENT = 1_000_000;

  public FactoryUpgradeEntry(ResourceLocation id, int parallelAdd, int speedNumerator, int speedDenominator, int energyNumerator, int energyDenominator, int fluidNumerator, int fluidDenominator) {
    this(id, parallelAdd, speedNumerator, speedDenominator, energyNumerator, energyDenominator, fluidNumerator,
        fluidDenominator, List.of());
  }

  public FactoryUpgradeEntry {
    Objects.requireNonNull(id, "id");
    recipeModifiers = List.copyOf(recipeModifiers);
    new RecipeModifiers(recipeModifiers);
    if (parallelAdd < 0
        || speedNumerator < 1
        || speedDenominator < 1
        || energyNumerator < 1
        || energyDenominator < 1
        || fluidNumerator < 1
        || fluidDenominator < 1
        || speedNumerator > MAX_RATIO_COMPONENT
        || speedDenominator > MAX_RATIO_COMPONENT
        || energyNumerator > MAX_RATIO_COMPONENT
        || energyDenominator > MAX_RATIO_COMPONENT
        || fluidNumerator > MAX_RATIO_COMPONENT
        || fluidDenominator > MAX_RATIO_COMPONENT) {
      throw new IllegalArgumentException("Invalid factory upgrade entry");
    }
  }

  public FactoryUpgradeEntry multipliedBy(int count) {
    if (count < 1 || count > 256) {
      throw new IllegalArgumentException("Upgrade stack count outside supported bound");
    }
    int parallel = Math.multiplyExact(parallelAdd, count);
    if ((long) recipeModifiers.size() * count > RecipeModifiers.MAX_RULES) {
      throw new IllegalArgumentException("Too many stacked upgrade recipe modifiers");
    }
    var rules = new ArrayList<RecipeModifier>();
    int speedNum = 1;
    int speedDen = 1;
    int energyNum = 1;
    int energyDen = 1;
    int fluidNum = 1;
    int fluidDen = 1;
    for (int index = 0; index < count; index++) {
      rules.addAll(recipeModifiers);
      speedNum = Math.multiplyExact(speedNum, speedNumerator);
      speedDen = Math.multiplyExact(speedDen, speedDenominator);
      energyNum = Math.multiplyExact(energyNum, energyNumerator);
      energyDen = Math.multiplyExact(energyDen, energyDenominator);
      fluidNum = Math.multiplyExact(fluidNum, fluidNumerator);
      fluidDen = Math.multiplyExact(fluidDen, fluidDenominator);
    }

    return new FactoryUpgradeEntry(id, parallel, speedNum, speedDen, energyNum, energyDen, fluidNum, fluidDen, rules);
  }

  public FactoryUpgradeEntry withRecipeModifiers(List<RecipeModifier> rules) {
    return new FactoryUpgradeEntry(
        id,
        parallelAdd,
        speedNumerator,
        speedDenominator,
        energyNumerator,
        energyDenominator,
        fluidNumerator,
        fluidDenominator,
        rules);
  }

  public FactoryUpgradeSnapshot snapshot(long revision) {
    return new FactoryUpgradeSnapshot(
        revision,
        Math.addExact(1, parallelAdd),
        speedNumerator,
        speedDenominator,
        energyNumerator,
        energyDenominator,
        fluidNumerator,
        fluidDenominator,
        recipeModifiers);
  }
}
