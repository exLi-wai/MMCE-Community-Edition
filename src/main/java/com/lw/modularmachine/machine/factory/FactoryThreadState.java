package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.api.upgrade.FactoryUpgradeSnapshot;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable independent logical recipe-thread state owned by a factory
 * controller.
 */
public record FactoryThreadState(
    String threadId,
    ResourceLocation machineId,
    ResourceLocation lockedRecipe,
    boolean enabled,
    int priority,
    int parallelLimit,
    long upgradeRevision,
    State state,
    Reason reason,
    long revision,
    FactoryUpgradeSnapshot upgradeSnapshot) {
  public static final int MIN_PRIORITY = -100_000;
  public static final int MAX_PRIORITY = 100_000;

  public FactoryThreadState {
    if (threadId == null
        || !threadId.matches("[a-zA-Z0-9_.-]{1,64}")
        || machineId == null
        || priority < MIN_PRIORITY
        || priority > MAX_PRIORITY
        || parallelLimit < 1
        || upgradeRevision < 0
        || revision < 0
        || upgradeSnapshot == null
        || upgradeSnapshot.parallelLimit() != parallelLimit
        || upgradeSnapshot.revision() != upgradeRevision
        || state == null
        || reason == null) {
      throw new IllegalArgumentException("Invalid factory thread state");
    }
  }

  public FactoryThreadState withState(State next, Reason nextReason) {
    if (next == state && nextReason == reason) {
      return this;
    }
    return new FactoryThreadState(
        threadId,
        machineId,
        lockedRecipe,
        enabled,
        priority,
        parallelLimit,
        upgradeRevision,
        next,
        nextReason,
        revision + 1,
        upgradeSnapshot);
  }

  public FactoryThreadState withEnabled(boolean nextEnabled) {
    if (nextEnabled == enabled) {
      return this;
    }
    return new FactoryThreadState(
        threadId,
        machineId,
        lockedRecipe,
        nextEnabled,
        priority,
        parallelLimit,
        upgradeRevision,
        state,
        reason,
        revision + 1,
        upgradeSnapshot);
  }

  public FactoryThreadState withPriority(int nextPriority) {
    if (nextPriority == priority) {
      return this;
    }

    return new FactoryThreadState(
        threadId,
        machineId,
        lockedRecipe,
        enabled,
        nextPriority,
        parallelLimit,
        upgradeRevision,
        state,
        reason,
        Math.incrementExact(revision),
        upgradeSnapshot);
  }

  public FactoryThreadState withLockedRecipe(ResourceLocation nextRecipe) {
    if (Objects.equals(nextRecipe, lockedRecipe)) {
      return this;
    }
    return new FactoryThreadState(
        threadId,
        machineId,
        nextRecipe,
        enabled,
        priority,
        parallelLimit,
        upgradeRevision,
        State.IDLE,
        Reason.NONE,
        revision + 1,
        upgradeSnapshot);
  }

  public FactoryThreadState withParallelLimit(int nextLimit, long nextUpgradeRevision) {
    if (nextLimit < 1 || nextUpgradeRevision < upgradeRevision) {
      throw new IllegalArgumentException("Invalid factory parallel limit");
    }
    if (nextLimit == parallelLimit && nextUpgradeRevision == upgradeRevision) {
      return this;
    }
    var nextSnapshot = new FactoryUpgradeSnapshot(
        nextUpgradeRevision,
        nextLimit,
        upgradeSnapshot.speedNumerator(),
        upgradeSnapshot.speedDenominator(),
        upgradeSnapshot.energyNumerator(),
        upgradeSnapshot.energyDenominator(),
        upgradeSnapshot.fluidNumerator(),
        upgradeSnapshot.fluidDenominator(),
        upgradeSnapshot.recipeModifiers());
    return new FactoryThreadState(
        threadId,
        machineId,
        lockedRecipe,
        enabled,
        priority,
        nextLimit,
        nextUpgradeRevision,
        State.IDLE,
        Reason.NONE,
        revision + 1,
        nextSnapshot);
  }

  public FactoryThreadState withUpgradeSnapshot(FactoryUpgradeSnapshot snapshot) {
    if (snapshot == null || snapshot.revision() < upgradeRevision) {
      throw new IllegalArgumentException("Invalid factory upgrade snapshot revision");
    }
    if (snapshot.equals(upgradeSnapshot)) {
      return this;
    }
    if (snapshot.revision() == upgradeRevision) {
      throw new IllegalArgumentException("Changed upgrade snapshot must advance its revision");
    }
    return new FactoryThreadState(
        threadId,
        machineId,
        lockedRecipe,
        enabled,
        priority,
        snapshot.parallelLimit(),
        snapshot.revision(),
        State.IDLE,
        Reason.NONE,
        revision + 1,
        snapshot);
  }

  public enum State {
    IDLE,
    WAITING,
    RUNNING,
    BLOCKED,
    INVALID
  }

  public enum Reason {
    NONE,
    STRUCTURE_PENDING,
    STRUCTURE_INVALID,
    NO_RECIPE,
    INPUT_MISSING,
    OUTPUT_BLOCKED,
    WAITING_ENERGY,
    WAITING_FLUID,
    SEARCH_BUDGET,
    STALE_RESOURCES,
    LOCKED_RECIPE_MISSING,
    CONTROLLER_UNAVAILABLE,
    CHECK_ERROR,
    OUTPUT_PLANNING,
    UPGRADE_COST_MISSING,
    UPGRADE_CONFLICT,
    WAITING_UPGRADE_COST,
    UPGRADE_ERROR,
    UPGRADE_DATA_PENDING,
    THREAD_LIMIT
  }
}
