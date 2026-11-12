package com.lw.modularmachine.api.upgrade;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import com.lw.modularmachine.machine.factory.UpgradeItemDataHandlers;
import com.lw.modularmachine.machine.factory.FactoryUpgradeDataSnapshots;

/** Controlled startup item-data proposals and registration. */
public final class FactoryUpgradeItemData {
  public static final String DATA_KEY = "mmcece:upgrade_data";
  public static final int MAX_BYTES = 16_384;
  public static final int MAX_TAGS = 1024;
  public static final int MAX_DEPTH = 32;

  @FunctionalInterface
  public interface Preparer {
    Update prepare(Context context, FactoryUpgradeEvents.Upgrade upgrade, CompoundTag currentData);
  }

  public record Context(
      ResourceLocation machine,
      ResourceLocation recipeId,
      ResourceLocation dimension,
      BlockPos controllerPosition,
      String threadId,
      int instances,
      long epoch,
      FactoryUpgradeSnapshot upgrades) {
    public Context {
      Objects.requireNonNull(machine, "machine");
      Objects.requireNonNull(recipeId, "recipeId");
      Objects.requireNonNull(dimension, "dimension");
      Objects.requireNonNull(controllerPosition, "controllerPosition");
      Objects.requireNonNull(threadId, "threadId");
      Objects.requireNonNull(upgrades, "upgrades");
      if (!threadId.matches("[a-zA-Z0-9_.-]{1,64}") || instances < 1 || epoch < 0) {
        throw new IllegalArgumentException("Invalid upgrade data preparation context");
      }
      controllerPosition = controllerPosition.immutable();
    }
  }

  public record Update(CompoundTag data) {
    public Update {
      data = FactoryUpgradeDataSnapshots.copy(data);
    }

    @Override
    public CompoundTag data() {
      return data.copy();
    }
  }

  private FactoryUpgradeItemData() {
  }

  public static void register(ResourceLocation id, Preparer handler) {
    UpgradeItemDataHandlers.register(id, handler);
  }

  public static void remove(ResourceLocation id) {
    UpgradeItemDataHandlers.remove(id);
  }

  public static void invalidate(ResourceLocation id) {
    UpgradeItemDataHandlers.invalidate(id);
  }

  public static boolean isRegistered(ResourceLocation id) {
    return UpgradeItemDataHandlers.isRegistered(id);
  }

  public static CompoundTag read(ItemStack stack) {
    return UpgradeItemDataHandlers.read(stack);
  }
}
