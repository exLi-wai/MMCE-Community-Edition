package com.lw.modularmachine.api.controller;

import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.api.recipe.RecipeLifecycleEvents.RunSnapshot;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * Detached ordinary/factory query values, captured on the owning server thread.
 */
public record ControllerSnapshot(
    ResourceLocation dimension,
    BlockPos controllerPosition,
    ResourceLocation machine,
    boolean factory,
    boolean formed,
    long tick,
    long generation,
    List<RunSnapshot> activeRecipes,
    List<RecipeThread> recipeThreads,
    int extraThreadCount,
    List<String> modifierReplacements,
    List<RecipeModifier> structureModifiers,
    List<MachineUpgrade> machineUpgrades,
    Map<String, DynamicPattern> dynamicPatterns) {
  public ControllerSnapshot {
    Objects.requireNonNull(dimension, "dimension");
    Objects.requireNonNull(machine, "machine");
    controllerPosition = controllerPosition.immutable();
    activeRecipes = List.copyOf(activeRecipes);
    recipeThreads = List.copyOf(recipeThreads);
    modifierReplacements = List.copyOf(modifierReplacements);
    structureModifiers = List.copyOf(structureModifiers);
    machineUpgrades = List.copyOf(machineUpgrades);
    dynamicPatterns = Map.copyOf(dynamicPatterns);
    if (tick < 0 || extraThreadCount < 0) {
      throw new IllegalArgumentException("Invalid controller query snapshot");
    }
  }

  public record RecipeThread(
      String threadId,
      boolean enabled,
      int priority,
      int parallelLimit,
      Optional<ResourceLocation> lockedRecipe,
      String state,
      String reason,
      long revision,
      List<RunSnapshot> activeRecipes) {
    public RecipeThread {
      Objects.requireNonNull(lockedRecipe, "lockedRecipe");
      activeRecipes = List.copyOf(activeRecipes);
    }

    public List<RunSnapshot> getActiveRecipeList() {
      return activeRecipes;
    }
  }

  public record MachineUpgrade(ResourceLocation id, int count, BlockPos busPosition) {
    public MachineUpgrade {
      Objects.requireNonNull(id, "id");
      busPosition = busPosition.immutable();
      if (count < 1) {
        throw new IllegalArgumentException("Invalid installed upgrade count");
      }
    }
  }

  public record DynamicPattern(
      String patternName,
      int size,
      int minSize,
      int maxSize,
      MachineOrientation orientation,
      BlockPos startOffset,
      BlockPos step) {
    public DynamicPattern {
      Objects.requireNonNull(patternName, "patternName");
      Objects.requireNonNull(orientation, "orientation");
      startOffset = startOffset.immutable();
      step = step.immutable();
    }

    public String getPatternName() {
      return patternName;
    }

    public int getSize() {
      return size;
    }

    public int getMinSize() {
      return minSize;
    }

    public int getMaxSize() {
      return maxSize;
    }

    public String getFacing() {
      return orientation.front().getSerializedName();
    }
  }

  public List<RunSnapshot> getActiveRecipeList() {
    return activeRecipes;
  }

  public List<RecipeThread> getRecipeThreadList() {
    return recipeThreads;
  }

  public int getExtraThreadCount() {
    return extraThreadCount;
  }

  public List<String> getFoundModifierReplacements() {
    return modifierReplacements;
  }

  public boolean hasModifierReplacement(String modifierName) {
    return modifierReplacements.contains(modifierName);
  }

  public boolean hasMachineUpgrade(String upgradeName) {
    return !getMachineUpgrade(upgradeName).isEmpty();
  }

  public List<MachineUpgrade> getMachineUpgrade(String upgradeName) {
    var id = ResourceLocation.parse(upgradeName);

    return machineUpgrades.stream().filter(upgrade -> upgrade.id().equals(id)).toList();
  }

  public DynamicPattern getDynamicPattern(String patternName) {
    return dynamicPatterns.get(patternName);
  }
}
