package com.lw.modularmachine.api.upgrade;

import com.lw.modularmachine.machine.factory.FactoryUpgradeCatalog;
import com.lw.modularmachine.machine.factory.FactoryUpgradeDataSnapshots;
import com.lw.modularmachine.machine.factory.UpgradeLifecyclePublisher;
import com.lw.modularmachine.machine.recipe.FrozenItemRun;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/** Read-only upgrade recipe notifications from active upgrade bus slots. */
public final class FactoryUpgradeEvents {
  @FunctionalInterface
  public interface Listener {
    void onEvent(Context context, Upgrade upgrade);
  }

  public enum Type {
    STARTED,
    FINISHED
  }

  public record Upgrade(
      ResourceLocation definitionId,
      ResourceLocation itemId,
      BlockPos busPosition,
      int slot,
      int count,
      FactoryUpgradeEntry effect,
      CompoundTag itemData,
      CompoundTag busData) {
    public Upgrade(ResourceLocation definitionId, ResourceLocation itemId, BlockPos busPosition, int slot, int count, FactoryUpgradeEntry effect, CompoundTag itemData) {
      this(definitionId, itemId, busPosition, slot, count, effect, itemData, new CompoundTag());
    }

    public Upgrade {
      Objects.requireNonNull(definitionId, "definitionId");
      Objects.requireNonNull(itemId, "itemId");
      Objects.requireNonNull(busPosition, "busPosition");
      Objects.requireNonNull(effect, "effect");
      Objects.requireNonNull(itemData, "itemData");
      if (slot < 0 || slot >= FactoryUpgradeCatalog.MAX_SLOTS || count < 1 || count > 256) {
        throw new IllegalArgumentException("Invalid upgrade event source");
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

  public record RunSnapshot(
      UUID runId,
      ResourceLocation recipeId,
      FrozenItemRun.State state,
      int progress,
      int duration) {
    public RunSnapshot {
      Objects.requireNonNull(runId, "runId");
      Objects.requireNonNull(recipeId, "recipeId");
      Objects.requireNonNull(state, "state");
      if (duration < 1
          || progress < 0
          || progress > duration
          || state == FrozenItemRun.State.OUTPUT_PENDING && progress != duration) {
        throw new IllegalArgumentException("Invalid event run snapshot");
      }
    }
  }

  public record Context(
      Type type,
      ResourceLocation machine,
      ResourceLocation dimension,
      BlockPos controllerPosition,
      String threadId,
      long tick,
      List<RunSnapshot> runs) {
    public Context {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(machine, "machine");
      Objects.requireNonNull(dimension, "dimension");
      Objects.requireNonNull(controllerPosition, "controllerPosition");
      Objects.requireNonNull(threadId, "threadId");
      Objects.requireNonNull(runs, "runs");
      if (threadId.isBlank() || threadId.length() > 64 || tick < 0 || runs.isEmpty()) {
        throw new IllegalArgumentException("Invalid factory upgrade event context");
      }
      controllerPosition = controllerPosition.immutable();
      runs = List.copyOf(runs);
    }
  }

  private FactoryUpgradeEvents() {
  }

  public static void register(ResourceLocation id, Listener listener) {
    UpgradeLifecyclePublisher.register(id, listener);
  }

  public static void remove(ResourceLocation id) {
    UpgradeLifecyclePublisher.remove(id);
  }

  public static void invalidate(ResourceLocation id) {
    UpgradeLifecyclePublisher.invalidate(id);
  }

  public static boolean isRegistered(ResourceLocation id) {
    return UpgradeLifecyclePublisher.isRegistered(id);
  }
}