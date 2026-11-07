package com.lw.modularmachine.machine.factory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import com.lw.modularmachine.api.upgrade.FactoryUpgradeRuntimeData.Phase;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeRuntimeData.Preparer;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeRuntimeData.Context;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeRuntimeData.Update;
import org.jetbrains.annotations.ApiStatus;

/** Internal grouping, callback registry and runtime proposal preparation. */
@ApiStatus.Internal
public final class UpgradeRuntimeDataHandlers {
  public record Subscription(ResourceLocation handlerId, ResourceLocation definitionId, List<Integer> slots) {
    public Subscription {
      Objects.requireNonNull(handlerId, "handlerId");
      Objects.requireNonNull(definitionId, "definitionId");
      slots = List.copyOf(slots);
      if (handlerId.toString().length() > 256
          || definitionId.toString().length() > 256
          || slots.isEmpty()
          || slots.size() > FactoryUpgradeCatalog.MAX_SLOTS
          || slots.stream().anyMatch(slot -> slot < 0 || slot >= FactoryUpgradeCatalog.MAX_SLOTS)
          || slots.stream().distinct().count() != slots.size()) {
        throw new IllegalArgumentException("Invalid grouped runtime upgrade subscription");
      }
    }
  }

  /**
   * Shared static grouping for initial compilation and conservative inventory
   * audits.
   */
  public static final class Subscriptions {
    private final Phase phase;
    private final Map<ResourceLocation, List<Integer>> slots = new LinkedHashMap<>();
    private final Map<ResourceLocation, ResourceLocation> handlers = new LinkedHashMap<>();

    public Subscriptions() {
      this(Phase.TICK);
    }

    public Subscriptions(Phase phase) {
      this.phase = Objects.requireNonNull(phase, "phase");
    }

    public void add(FactoryUpgradeDefinition definition, int slot) {
      var handler = phase == Phase.TICK ? definition.tickDataHandler() : definition.finishDataHandler();
      if (handler != null) {
        slots.computeIfAbsent(definition.effect().id(), ignored -> new ArrayList<>()).add(slot);
        handlers.put(definition.effect().id(), handler);
      }
    }

    public List<Subscription> build() {
      return slots.entrySet().stream()
          .map(entry -> new Subscription(handlers.get(entry.getKey()), entry.getKey(), entry.getValue())).toList();
    }
  }

  private static final FactoryUpgradeHandlers<Preparer> HANDLERS = new FactoryUpgradeHandlers<>();

  private UpgradeRuntimeDataHandlers() {
  }

  public static void register(ResourceLocation id, Preparer preparer) {
    HANDLERS.register(id, preparer);
  }

  public static void remove(ResourceLocation id) {
    HANDLERS.remove(id);
  }

  public static void invalidate(ResourceLocation id) {
    HANDLERS.invalidate(id);
  }

  public static boolean isRegistered(ResourceLocation id) {
    return HANDLERS.find(id) != null || FactoryUpgradeKubeJSHandlers.runtimeData(id) != null;
  }

  static Update prepare(Subscription subscription, Context context, CompoundTag currentData) {
    FactoryUpgradeHandlers.requireServer();
    var preparer = HANDLERS.find(subscription.handlerId());
    if (preparer == null) {
      preparer = FactoryUpgradeKubeJSHandlers.runtimeData(subscription.handlerId());
    }
    if (preparer == null) {
      throw new IllegalStateException("Missing runtime upgrade handler: " + subscription.handlerId());
    }
    var result = Objects.requireNonNull(preparer.prepare(context, currentData.copy()), "Runtime upgrade result");
    if (FactoryUpgradeService.snapshot().epoch() != context.epoch()) {
      throw new IllegalStateException("Runtime upgrade result lost its definition epoch");
    }

    return result;
  }

  static void clearOnServerStop() {
    HANDLERS.clear();
  }
}
