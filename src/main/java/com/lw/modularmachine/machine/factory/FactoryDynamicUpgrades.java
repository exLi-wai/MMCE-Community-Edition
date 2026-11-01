package com.lw.modularmachine.machine.factory;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Server-owned dynamic effect functions; no callback is serialized or exposed
 * in snapshots.
 */
public final class FactoryDynamicUpgrades {
  public static final int MAX_HANDLERS = FactoryUpgradeHandlers.MAX_HANDLERS;

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

  private static final FactoryUpgradeHandlers<Evaluator> handlers = new FactoryUpgradeHandlers<>();

  public static boolean isRegistered(ResourceLocation id) {
    return handlers.find(id) != null || FactoryUpgradeKubeJSHandlers.dynamic(id) != null;
  }

  private FactoryDynamicUpgrades() {
  }

  public static void register(ResourceLocation id, Evaluator evaluator) {
    handlers.register(id, evaluator);
  }

  public static void remove(ResourceLocation id) {
    handlers.remove(id);
  }

  public static void invalidate(ResourceLocation id) {
    handlers.invalidate(id);
  }

  static FactoryUpgradeEntry evaluate(FactoryUpgradeDefinition definition, ItemStack stack, ResourceLocation machine, BlockPos position, int slot, long epoch, FactoryUpgradeEntry baseEffect, CompoundTag busData) {
    var server = FactoryUpgradeHandlers.requireServer();
    var evaluator = handlers.find(definition.dynamicEffect());
    if (evaluator == null) {
      evaluator = FactoryUpgradeKubeJSHandlers.dynamic(definition.dynamicEffect());
    }
    if (evaluator == null) {
      throw new IllegalArgumentException("Missing dynamic upgrade handler: " + definition.dynamicEffect());
    }
    var saved = stack.save(server.registryAccess());
    if (!(saved instanceof CompoundTag itemData)) {
      throw new IllegalArgumentException("Dynamic item data did not encode as a compound");
    }
    var context = new Context(
        definition.effect().id(),
        definition.item(),
        machine,
        position,
        slot,
        stack.getCount(),
        epoch,
        baseEffect,
        itemData,
        busData);
    if (FactoryUpgradeService.snapshot().epoch() != epoch) {
      throw new IllegalStateException("Dynamic context lost its catalog epoch before evaluation");
    }
    var result = Objects.requireNonNull(evaluator.evaluate(context), "Dynamic effect result");
    if (!result.id().equals(baseEffect.id()) || FactoryUpgradeService.snapshot().epoch() != epoch) {
      throw new IllegalStateException("Dynamic result changed identity or its catalog epoch");
    }
    result.snapshot(0);

    return result;
  }

  static void clearOnServerStop() {
    handlers.clear();
  }
}
