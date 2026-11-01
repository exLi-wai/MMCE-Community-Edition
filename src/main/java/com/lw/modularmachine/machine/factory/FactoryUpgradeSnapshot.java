package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.machine.recipe.RecipeModifier;
import com.lw.modularmachine.machine.recipe.RecipeModifiers;
import java.util.ArrayList;
import java.util.List;

/** Immutable bounded upgrade effects captured for new factory starts. */
public record FactoryUpgradeSnapshot(
    long revision,
    int parallelLimit,
    int speedNumerator,
    int speedDenominator,
    int energyNumerator,
    int energyDenominator,
    int fluidNumerator,
    int fluidDenominator,
    List<RecipeModifier> recipeModifiers) {
  public static final int MAX_SPEED_STEPS_PER_TICK = 256;

  public FactoryUpgradeSnapshot(long revision, int parallelLimit, int speedNumerator, int speedDenominator, int energyNumerator, int energyDenominator, int fluidNumerator, int fluidDenominator) {
    this(revision, parallelLimit, speedNumerator, speedDenominator, energyNumerator, energyDenominator, fluidNumerator,
        fluidDenominator, List.of());
  }

  public FactoryUpgradeSnapshot {
    recipeModifiers = List.copyOf(recipeModifiers);
    new RecipeModifiers(recipeModifiers);
    if (revision < 0
        || parallelLimit < 1
        || speedNumerator < 1
        || speedDenominator < 1
        || energyNumerator < 1
        || energyDenominator < 1
        || fluidNumerator < 1
        || fluidDenominator < 1
        || speedNumerator > (long) speedDenominator * MAX_SPEED_STEPS_PER_TICK) {
      throw new IllegalArgumentException("Invalid factory upgrade snapshot");
    }
  }

  public static FactoryUpgradeSnapshot identity(long revision) {
    return new FactoryUpgradeSnapshot(revision, 1, 1, 1, 1, 1, 1, 1);
  }

  public FactoryUpgradeSnapshot withRevision(long revision) {
    return new FactoryUpgradeSnapshot(
        revision,
        parallelLimit,
        speedNumerator,
        speedDenominator,
        energyNumerator,
        energyDenominator,
        fluidNumerator,
        fluidDenominator,
        recipeModifiers);
  }

  public static FactoryUpgradeSnapshot compose(List<FactoryUpgradeSnapshot> snapshots) {
    if (snapshots == null || snapshots.isEmpty()) {
      throw new IllegalArgumentException("No factory upgrades");
    }
    long revision = 0;
    long parallel = 1;
    long speedNum = 1;
    long speedDen = 1;
    long energyNum = 1;
    long energyDen = 1;
    long fluidNum = 1;
    long fluidDen = 1;
    var rules = new ArrayList<RecipeModifier>();
    for (var snapshot : snapshots) {
      if (snapshot == null) {
        throw new IllegalArgumentException("Null factory upgrade");
      }
      revision = Math.max(revision, snapshot.revision());
      if (rules.size() + snapshot.recipeModifiers().size() > RecipeModifiers.MAX_RULES) {
        throw new IllegalArgumentException("Too many composed upgrade recipe modifiers");
      }
      rules.addAll(snapshot.recipeModifiers());
      parallel = Math.addExact(parallel, snapshot.parallelLimit() - 1L);
      speedNum = Math.multiplyExact(speedNum, snapshot.speedNumerator());
      speedDen = Math.multiplyExact(speedDen, snapshot.speedDenominator());
      energyNum = Math.multiplyExact(energyNum, snapshot.energyNumerator());
      energyDen = Math.multiplyExact(energyDen, snapshot.energyDenominator());
      fluidNum = Math.multiplyExact(fluidNum, snapshot.fluidNumerator());
      fluidDen = Math.multiplyExact(fluidDen, snapshot.fluidDenominator());
    }
    return new FactoryUpgradeSnapshot(
        revision,
        Math.toIntExact(parallel),
        Math.toIntExact(speedNum),
        Math.toIntExact(speedDen),
        Math.toIntExact(energyNum),
        Math.toIntExact(energyDen),
        Math.toIntExact(fluidNum),
        Math.toIntExact(fluidDen),
        rules);
  }

  public int durationFor(int baseDuration) {
    if (baseDuration <= 0) {
      throw new IllegalArgumentException("Invalid base duration");
    }
    long scaled = (long) baseDuration * speedDenominator;
    long rounded = (scaled + speedNumerator - 1) / speedNumerator;
    return Math.toIntExact(Math.max(1, rounded));
  }

  public int stepsPerTick() {
    long steps = ((long) speedNumerator + speedDenominator - 1) / speedDenominator;
    return Math.toIntExact(Math.max(1, Math.min(MAX_SPEED_STEPS_PER_TICK, steps)));
  }

  public long energyPerTick(long base) {
    return scale(base, energyNumerator, energyDenominator);
  }

  public long fluidPerTick(long base) {
    return scale(base, fluidNumerator, fluidDenominator);
  }

  private static long scale(long base, int numerator, int denominator) {
    if (base < 0) {
      throw new IllegalArgumentException("Negative upgrade base");
    }
    return Math.addExact(Math.multiplyExact(base, numerator), denominator - 1) / denominator;
  }
}
