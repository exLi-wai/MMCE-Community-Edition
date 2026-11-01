package com.lw.modularmachine.machine.factory;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Pure startup transforms for one owned custom-data field, never live inventory
 * writes.
 */
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

  public record Subscription(ResourceLocation handlerId, FactoryUpgradeEvents.Upgrade upgrade) {
    public Subscription {
      Objects.requireNonNull(handlerId, "handlerId");
      Objects.requireNonNull(upgrade, "upgrade");
    }
  }

  private static final FactoryUpgradeHandlers<Preparer> HANDLERS = new FactoryUpgradeHandlers<>();

  private FactoryUpgradeItemData() {
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
    return preparer(id) != null;
  }

  private static Preparer preparer(ResourceLocation id) {
    var host = HANDLERS.find(id);

    return host == null ? FactoryUpgradeKubeJSHandlers.startItemData(id) : host;
  }

  public static CompoundTag read(ItemStack stack) {
    FactoryUpgradeHandlers.requireServer();
    var custom = stack.get(DataComponents.CUSTOM_DATA);
    var data = custom == null ? new CompoundTag() : custom.copyTag();
    if (data.contains(DATA_KEY) && !data.contains(DATA_KEY, Tag.TAG_COMPOUND)) {
      throw new IllegalArgumentException("Owned upgrade item data is not a compound");
    }

    return new Update(data.getCompound(DATA_KEY)).data();
  }

  static Update prepare(Subscription subscription, Context context, ItemStack stack) {
    FactoryUpgradeHandlers.requireServer();
    var preparer = preparer(subscription.handlerId());
    if (preparer == null) {
      throw new IllegalStateException("Missing upgrade data handler: " + subscription.handlerId());
    }
    var result = Objects
        .requireNonNull(preparer.prepare(context, subscription.upgrade(), read(stack)), "Upgrade data result");
    if (FactoryUpgradeService.snapshot().epoch() != context.epoch()) {
      throw new IllegalStateException("Upgrade item data result lost its definition epoch");
    }

    return result;
  }

  static boolean apply(ItemStack stack, Update update) {
    var next = update.data();
    var custom = stack.get(DataComponents.CUSTOM_DATA);
    var data = custom == null ? new CompoundTag() : custom.copyTag();
    if (read(stack).equals(next) && (!next.isEmpty() || !data.contains(DATA_KEY))) {
      return false;
    }
    if (next.isEmpty()) {
      data.remove(DATA_KEY);
    } else {
      data.put(DATA_KEY, next);
    }
    if (data.isEmpty()) {
      stack.remove(DataComponents.CUSTOM_DATA);
    } else {
      stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    }

    return true;
  }

  static void clearOnServerStop() {
    HANDLERS.clear();
  }

}
