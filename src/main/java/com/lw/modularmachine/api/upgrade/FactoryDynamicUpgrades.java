package com.lw.modularmachine.api.upgrade;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import com.lw.modularmachine.machine.factory.DynamicUpgradeHandlers;
import com.lw.modularmachine.machine.factory.FactoryUpgradeCatalog;
import com.lw.modularmachine.machine.factory.FactoryUpgradeDataSnapshots;
import com.lw.modularmachine.util.callback.ServerCallbackRegistry;

/** Dynamic upgrade registration and immutable callback data. */
public final class FactoryDynamicUpgrades {
  public static final int MAX_HANDLERS = ServerCallbackRegistry.MAX_HANDLERS;

  @FunctionalInterface
  public interface Evaluator {
    FactoryUpgradeEntry evaluate(Context context);
  }

  public record Context(
      ResourceLocation definitionId,
      ResourceLocation itemId,
      ResourceLocation machine,
      BlockPos busPosition,
      int slot,
      int count,
      long epoch,
      FactoryUpgradeEntry baseEffect,
      CompoundTag itemData,
      CompoundTag busData) {
    public Context(ResourceLocation definitionId, ResourceLocation itemId, ResourceLocation machine, BlockPos busPosition, int slot, int count, long epoch, FactoryUpgradeEntry baseEffect, CompoundTag itemData) {
      this(definitionId, itemId, machine, busPosition, slot, count, epoch, baseEffect, itemData, new CompoundTag());
    }

    public Context {
      Objects.requireNonNull(definitionId, "definitionId");
      Objects.requireNonNull(itemId, "itemId");
      Objects.requireNonNull(busPosition, "busPosition");
      Objects.requireNonNull(baseEffect, "baseEffect");
      Objects.requireNonNull(itemData, "itemData");
      if (slot < 0 || slot >= FactoryUpgradeCatalog.MAX_SLOTS || count < 1 || count > 256 || epoch < 0) {
        throw new IllegalArgumentException("Invalid dynamic upgrade context");
      }
      busPosition = busPosition.immutable();
      itemData = itemData.copy();
      busData = FactoryUpgradeDataSnapshots.copy(busData);
    }

    @Override
    public CompoundTag itemData() {
      return itemData.copy();
    }

    @Override
    public CompoundTag busData() {
      return busData.copy();
    }
  }

  private FactoryDynamicUpgrades() {
  }

  public static void register(ResourceLocation id, Evaluator handler) {
    DynamicUpgradeHandlers.register(id, handler);
  }

  public static void remove(ResourceLocation id) {
    DynamicUpgradeHandlers.remove(id);
  }

  public static void invalidate(ResourceLocation id) {
    DynamicUpgradeHandlers.invalidate(id);
  }

  public static boolean isRegistered(ResourceLocation id) {
    return DynamicUpgradeHandlers.isRegistered(id);
  }

}
