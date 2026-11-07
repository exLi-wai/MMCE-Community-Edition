package com.lw.modularmachine.api.upgrade;

import com.lw.modularmachine.machine.factory.UpgradeRecipeChecks;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/**
 * Registers read-only candidate checks over immutable recipe and upgrade data.
 */
public final class FactoryUpgradeRecipeChecks {
  @FunctionalInterface
  public interface Checker {
    Decision check(Context context, FactoryUpgradeEvents.Upgrade upgrade);
  }

  public enum Decision {
    ALLOW,
    REJECT
  }

  public record Context(
      ResourceLocation machine,
      ResourceLocation recipeId,
      ResourceLocation dimension,
      BlockPos controllerPosition,
      String threadId,
      long epoch,
      FactoryUpgradeSnapshot upgrades,
      CompoundTag recipeData) {
    public Context {
      Objects.requireNonNull(machine, "machine");
      Objects.requireNonNull(recipeId, "recipeId");
      Objects.requireNonNull(dimension, "dimension");
      Objects.requireNonNull(controllerPosition, "controllerPosition");
      Objects.requireNonNull(threadId, "threadId");
      Objects.requireNonNull(upgrades, "upgrades");
      Objects.requireNonNull(recipeData, "recipeData");
      if (!threadId.matches("[a-zA-Z0-9_.-]{1,64}") || epoch < 0) {
        throw new IllegalArgumentException("Invalid recipe check context");
      }
      controllerPosition = controllerPosition.immutable();
      recipeData = recipeData.copy();
    }

    @Override
    public CompoundTag recipeData() {
      return recipeData.copy();
    }
  }

  private FactoryUpgradeRecipeChecks() {
  }

  public static void register(ResourceLocation id, Checker checker) {
    UpgradeRecipeChecks.register(id, checker);
  }

  public static void remove(ResourceLocation id) {
    UpgradeRecipeChecks.remove(id);
  }

  public static void invalidate(ResourceLocation id) {
    UpgradeRecipeChecks.invalidate(id);
  }

  public static boolean isRegistered(ResourceLocation id) {
    return UpgradeRecipeChecks.isRegistered(id);
  }
}