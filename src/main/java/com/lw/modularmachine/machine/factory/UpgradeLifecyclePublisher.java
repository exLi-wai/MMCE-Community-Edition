package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import com.lw.modularmachine.machine.recipe.FrozenItemRun;
import com.lw.modularmachine.machine.recipe.RecipeLifecyclePublisher;
import com.lw.modularmachine.machine.smart.SmartInterfaceEventPublisher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import com.lw.modularmachine.api.upgrade.FactoryUpgradeEntry;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeEvents.Context;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeEvents.Listener;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeEvents.RunSnapshot;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeEvents.Type;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeEvents.Upgrade;
import org.jetbrains.annotations.ApiStatus;

/** Internal committed upgrade event dispatch and callback guards. */
@ApiStatus.Internal
public final class UpgradeLifecyclePublisher {
  public record Subscription(ResourceLocation handlerId, Upgrade upgrade) {
    public Subscription {
      Objects.requireNonNull(handlerId, "handlerId");
      Objects.requireNonNull(upgrade, "upgrade");
    }
  }

  private record Delivery(ResourceLocation handlerId, Listener listener, Upgrade upgrade) {
  }

  private static final FactoryUpgradeHandlers<Listener> HANDLERS = new FactoryUpgradeHandlers<>();
  private static final Map<ResourceLocation, Listener> REPORTED_FAILURES = new HashMap<>();

  private UpgradeLifecyclePublisher() {
  }

  public static void register(ResourceLocation id, Listener listener) {
    if (HANDLERS.register(id, listener)) {
      REPORTED_FAILURES.remove(id);
    }
  }

  public static void remove(ResourceLocation id) {
    if (HANDLERS.remove(id)) {
      REPORTED_FAILURES.remove(id);
    }
  }

  public static void invalidate(ResourceLocation id) {
    HANDLERS.invalidate(id);
    REPORTED_FAILURES.remove(id);
  }

  public static boolean isDispatching(UpgradeBusOwner factory) {
    return FactoryUpgradeCallbacks.contains(factory)
        || RecipeLifecyclePublisher.isDispatching()
        || SmartInterfaceEventPublisher.isDispatching(factory);
  }

  public static boolean isDispatchingAny() {
    return FactoryUpgradeCallbacks.any() || RecipeLifecyclePublisher.isDispatching();
  }

  public static boolean isRegistered(ResourceLocation handlerId) {
    return listener(handlerId) != null;
  }

  private static Listener listener(ResourceLocation handlerId) {
    var host = HANDLERS.find(handlerId);

    return host == null ? FactoryUpgradeKubeJSHandlers.lifecycle(handlerId) : host;
  }

  public static Subscription snapshot(ResourceLocation handlerId, FactoryUpgradeDefinition definition, ItemStack stack, BlockPos busPosition, int slot, FactoryUpgradeEntry effect) {
    if (!isRegistered(handlerId)) {
      throw new IllegalArgumentException("Missing factory upgrade event handler: " + handlerId);
    }

    return new Subscription(handlerId, captureUpgrade(definition, stack, busPosition, slot, effect));
  }

  public static Upgrade captureUpgrade(FactoryUpgradeDefinition definition, ItemStack stack, BlockPos busPosition, int slot, FactoryUpgradeEntry effect) {
    return captureUpgrade(definition, stack, busPosition, slot, effect, new CompoundTag());
  }

  public static Upgrade captureUpgrade(FactoryUpgradeDefinition definition, ItemStack stack, BlockPos busPosition, int slot, FactoryUpgradeEntry effect, CompoundTag busData) {
    var server = FactoryUpgradeHandlers.requireServer();
    var saved = stack.save(server.registryAccess());
    if (!(saved instanceof CompoundTag itemData)) {
      throw new IllegalArgumentException("Upgrade event item did not encode as a compound");
    }
    var itemId = stack.getItem().builtInRegistryHolder().key().location();

    return new Upgrade(
        definition.effect().id(),
        itemId,
        busPosition,
        slot,
        stack.getCount(),
        effect,
        itemData,
        busData);
  }

  public static void recipeStarted(UpgradeBusOwner factory, String threadId, List<FrozenItemRun> runs) {
    if (factory instanceof FactoryControllerBlockEntity controller) {
      RecipeLifecyclePublisher.started(controller, threadId, runs);
    } else if (factory instanceof MachineControllerBlockEntity controller && runs != null && runs.size() == 1) {
      RecipeLifecyclePublisher.started(controller, runs.getFirst());
    }
    dispatch(factory, threadId, runs, Type.STARTED);
  }

  public static void recipeFinished(UpgradeBusOwner factory, String threadId, List<FrozenItemRun> runs) {
    if (factory instanceof FactoryControllerBlockEntity controller) {
      RecipeLifecyclePublisher.finished(controller, threadId, runs);
    } else if (factory instanceof MachineControllerBlockEntity controller && runs != null && runs.size() == 1) {
      RecipeLifecyclePublisher.finished(controller, runs.getFirst());
    }
    dispatch(factory, threadId, runs, Type.FINISHED);
  }

  static void clearOnServerStop() {
    HANDLERS.clear();
    REPORTED_FAILURES.clear();
  }

  private static void dispatch(UpgradeBusOwner factory, String threadId, List<FrozenItemRun> runs, Type type) {
    if (factory == null
        || runs == null
        || runs.isEmpty()
        || isDispatching(factory)
        || !(factory.getLevel() instanceof ServerLevel level)) {
      return;
    }
    FactoryUpgradeHandlers.requireServer();
    if (HANDLERS.isEmpty() && !FactoryUpgradeKubeJSHandlers.hasEvents()) {
      return;
    }
    var deliveries = new ArrayList<Delivery>();
    var lockedBuses = new ArrayList<UpgradeBusBlockEntity>();
    try {
      for (var bus : notificationBuses(factory, level)) {
        if (!bus.upgradeCatalogValid(factory) || !bus.beginLifecycleNotification(factory)) {
          continue;
        }
        lockedBuses.add(bus);
        for (var subscription : bus.lifecycleSubscriptions(factory)) {
          var listener = listener(subscription.handlerId());
          if (listener != null) {
            deliveries.add(new Delivery(subscription.handlerId(), listener, subscription.upgrade()));
          }
        }
      }
      if (deliveries.isEmpty()) {
        return;
      }
      if (!FactoryUpgradeCallbacks.enter(factory)) {
        return;
      }
      var snapshots = runs.stream()
          .map(run -> new RunSnapshot(run.runId(), run.recipeId(), run.state(), run.progress(), run.duration()))
          .toList();
      var context = new Context(
          type,
          factory.machineId(),
          level.dimension().location(),
          factory.getBlockPos(),
          threadId,
          level.getGameTime(),
          snapshots);
      for (var delivery : deliveries) {
        try {
          delivery.listener().onEvent(context, delivery.upgrade());
        } catch (RuntimeException failure) {
          if (listener(delivery.handlerId()) == delivery.listener()
              && REPORTED_FAILURES.get(delivery.handlerId()) != delivery.listener()) {
            REPORTED_FAILURES.put(delivery.handlerId(), delivery.listener());
            ModularMachine.LOGGER.warn(
                "Factory upgrade event {} failed for handler {} at {}",
                type,
                delivery.handlerId(),
                factory.getBlockPos(),
                failure);
          }
        }
      }
    } catch (RuntimeException failure) {
      ModularMachine.LOGGER.warn("Factory upgrade event {} capture failed at {}", type, factory.getBlockPos(), failure);
    } finally {
      FactoryUpgradeCallbacks.exit(factory);
      for (int index = lockedBuses.size() - 1; index >= 0; index--) {
        lockedBuses.get(index).endLifecycleNotification();
      }
    }
  }

  private static List<UpgradeBusBlockEntity> notificationBuses(UpgradeBusOwner owner, ServerLevel level) {
    if (!(owner instanceof FactoryControllerBlockEntity factory)) {
      return owner.upgradeCallbackBuses();
    }
    var result = new ArrayList<UpgradeBusBlockEntity>();
    for (var source : factory.upgradeSources()) {
      if (level.hasChunkAt(source.position())
          && level.getBlockEntity(source.position()) instanceof UpgradeBusBlockEntity bus
          && bus.upgradeCatalog(owner) == source.catalog()) {
        result.add(bus);
      }
    }

    return List.copyOf(result);
  }
}
