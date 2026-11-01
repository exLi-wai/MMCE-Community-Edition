package com.lw.modularmachine.machine.factory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/**
 * Pure tick proposals; callers apply them only inside the existing resource/run
 * transaction.
 */
public final class FactoryUpgradeRuntimeData {
  public enum Phase {
    TICK,
    FINISHED;

    public ResourceLocation handler(FactoryUpgradeDefinition definition) {
      return this == TICK ? definition.tickDataHandler() : definition.finishDataHandler();
    }
  }

  @FunctionalInterface
  public interface Preparer {
    Update prepare(Context context, CompoundTag currentBusData);
  }

  public record Run(
      UUID runId,
      ResourceLocation recipe,
      int progress,
      int duration,
      int instances,
      long lastProgressTick) {
    public Run {
      Objects.requireNonNull(runId, "runId");
      Objects.requireNonNull(recipe, "recipe");
      if (progress < 0 || duration < 1 || progress > duration || instances < 1) {
        throw new IllegalArgumentException("Invalid runtime upgrade run");
      }
    }
  }

  public record Item(
      int slot,
      ResourceLocation item,
      int originalCount,
      int count,
      int damage,
      int maximumDamage,
      CompoundTag data) {
    public Item {
      Objects.requireNonNull(item, "item");
      if (slot < 0
          || slot >= FactoryUpgradeCatalog.MAX_SLOTS
          || originalCount < 1
          || count < 0
          || damage < 0
          || maximumDamage < 0) {
        throw new IllegalArgumentException("Invalid runtime upgrade item");
      }
      data = FactoryUpgradeDataSnapshots.copy(data);
    }

    @Override
    public CompoundTag data() {
      return data.copy();
    }
  }

  public record Context(
      Phase phase,
      ResourceLocation machine,
      ResourceLocation dimension,
      BlockPos controller,
      String threadId,
      long preparedAtTick,
      long epoch,
      long instances,
      List<Run> runs,
      ResourceLocation definition,
      BlockPos bus,
      List<Item> items) {
    public Context {
      Objects.requireNonNull(phase, "phase");
      Objects.requireNonNull(machine, "machine");
      Objects.requireNonNull(dimension, "dimension");
      Objects.requireNonNull(controller, "controller");
      Objects.requireNonNull(threadId, "threadId");
      Objects.requireNonNull(definition, "definition");
      Objects.requireNonNull(bus, "bus");
      if (!threadId.matches("[a-zA-Z0-9_.-]{1,64}") || epoch < 0 || instances < 1 || runs.isEmpty()) {
        throw new IllegalArgumentException("Invalid runtime upgrade context");
      }
      controller = controller.immutable();
      bus = bus.immutable();
      runs = List.copyOf(runs);
      items = List.copyOf(items);
    }
  }

  public record ItemUpdate(int slot, Optional<FactoryUpgradeItemData.Update> data, FactoryUpgradeStartCost cost) {
    public ItemUpdate {
      Objects.requireNonNull(data, "data");
      Objects.requireNonNull(cost, "cost");
      if (slot < 0 || slot >= FactoryUpgradeCatalog.MAX_SLOTS) {
        throw new IllegalArgumentException("Invalid runtime update slot");
      }
    }

    public static ItemUpdate data(int slot, CompoundTag data) {
      return new ItemUpdate(
          slot,
          Optional.of(new FactoryUpgradeItemData.Update(data)),
          new FactoryUpgradeStartCost(0, 0));
    }
  }

  public record Update(Optional<FactoryUpgradeBusData.Update> busData, List<ItemUpdate> items) {
    public Update {
      Objects.requireNonNull(busData, "busData");
      items = List.copyOf(items);
      if (items.size() > FactoryUpgradeCatalog.MAX_SLOTS
          || items.stream().map(ItemUpdate::slot).distinct().count() != items.size()) {
        throw new IllegalArgumentException("Duplicate or excessive runtime upgrade writes");
      }
    }

    public static Update unchanged() {
      return new Update(Optional.empty(), List.of());
    }

    public static Update bus(CompoundTag data) {
      return new Update(Optional.of(new FactoryUpgradeBusData.Update(data)), List.of());
    }
  }

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
      var handler = phase.handler(definition);
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

  private FactoryUpgradeRuntimeData() {
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
