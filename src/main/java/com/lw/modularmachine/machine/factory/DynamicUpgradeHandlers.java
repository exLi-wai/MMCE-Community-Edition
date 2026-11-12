package com.lw.modularmachine.machine.factory;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import com.lw.modularmachine.api.upgrade.FactoryDynamicUpgrades.Context;
import com.lw.modularmachine.api.upgrade.FactoryDynamicUpgrades.Evaluator;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeEntry;
import org.jetbrains.annotations.ApiStatus;

/** Internal upgrade callback registry and evaluation. */
@ApiStatus.Internal
public final class DynamicUpgradeHandlers {
  private static final FactoryUpgradeHandlers<Evaluator> handlers = new FactoryUpgradeHandlers<>();

  public static boolean isRegistered(ResourceLocation id) {
    return handlers.find(id) != null || FactoryUpgradeKubeJSHandlers.dynamic(id) != null;
  }

  private DynamicUpgradeHandlers() {
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
