package com.lw.modularmachine.api.upgrade;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import com.lw.modularmachine.machine.factory.UpgradeRuntimeDataHandlers;
import com.lw.modularmachine.machine.factory.FactoryUpgradeCatalog;
import com.lw.modularmachine.machine.factory.FactoryUpgradeDataSnapshots;

/** Controlled runtime upgrade proposals and registration. */
public final class FactoryUpgradeRuntimeData {
  public enum Phase {
    TICK,
    FINISHED;

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

  private FactoryUpgradeRuntimeData() {
  }

  public static void register(ResourceLocation id, Preparer handler) {
    UpgradeRuntimeDataHandlers.register(id, handler);
  }

  public static void remove(ResourceLocation id) {
    UpgradeRuntimeDataHandlers.remove(id);
  }

  public static void invalidate(ResourceLocation id) {
    UpgradeRuntimeDataHandlers.invalidate(id);
  }

  public static boolean isRegistered(ResourceLocation id) {
    return UpgradeRuntimeDataHandlers.isRegistered(id);
  }

}
