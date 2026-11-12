package com.lw.modularmachine.machine.factory;

import java.util.Objects;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeItemData.Context;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeItemData.Preparer;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeItemData.Update;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeEvents;
import static com.lw.modularmachine.api.upgrade.FactoryUpgradeItemData.DATA_KEY;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal startup item-data registry, preparation and private inventory
 * transforms.
 */
@ApiStatus.Internal
public final class UpgradeItemDataHandlers {
  public record Subscription(ResourceLocation handlerId, FactoryUpgradeEvents.Upgrade upgrade) {
    public Subscription {
      Objects.requireNonNull(handlerId, "handlerId");
      Objects.requireNonNull(upgrade, "upgrade");
    }
  }

  private static final FactoryUpgradeHandlers<Preparer> HANDLERS = new FactoryUpgradeHandlers<>();

  private UpgradeItemDataHandlers() {
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
