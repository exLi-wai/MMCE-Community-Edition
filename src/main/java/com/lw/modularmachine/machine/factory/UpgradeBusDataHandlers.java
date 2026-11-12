package com.lw.modularmachine.machine.factory;

import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeBusData.Context;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeBusData.Preparer;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeBusData.Update;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeItemData;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeEvents;
import org.jetbrains.annotations.ApiStatus;

/** Internal persistent bus data, callback registry and preparation. */
@ApiStatus.Internal
public final class UpgradeBusDataHandlers {
  public static final String NBT_KEY = "upgrade_custom_data";
  public static final int MAX_ENTRIES = 256;
  public static final int MAX_BYTES = 65_536;
  private static final int MAX_TAGS = 4096;

  public record Subscription(
      ResourceLocation handlerId,
      ResourceLocation definitionId,
      BlockPos busPosition,
      List<FactoryUpgradeEvents.Upgrade> upgrades) {
    public Subscription {
      Objects.requireNonNull(handlerId, "handlerId");
      validateSources(definitionId, busPosition, upgrades);
      busPosition = busPosition.immutable();
      upgrades = List.copyOf(upgrades);
    }
  }

  public static final class State {
    private static final State EMPTY = new State(new CompoundTag());
    private final CompoundTag entries;

    private State(CompoundTag entries) {
      this.entries = entries;
    }

    public static State empty() {
      return EMPTY;
    }

    public static State load(CompoundTag data) {
      var owned = FactoryUpgradeDataSnapshots.copy(data, MAX_BYTES, MAX_TAGS, FactoryUpgradeItemData.MAX_DEPTH + 1);
      if (owned.size() > MAX_ENTRIES) {
        throw new IllegalArgumentException("Too many upgrade bus data entries");
      }
      for (String key : owned.getAllKeys()) {
        if (key.length() > 256 || !owned.contains(key, Tag.TAG_COMPOUND)) {
          throw new IllegalArgumentException("Invalid upgrade bus data entry");
        }
        try {
          if (!ResourceLocation.parse(key).toString().equals(key)) {
            throw new IllegalArgumentException("Upgrade bus data needs canonical definition IDs");
          }
        } catch (RuntimeException invalid) {
          throw new IllegalArgumentException("Invalid upgrade bus definition ID", invalid);
        }
        FactoryUpgradeDataSnapshots.validate(
            owned.getCompound(key),
            FactoryUpgradeItemData.MAX_BYTES,
            FactoryUpgradeItemData.MAX_TAGS,
            FactoryUpgradeItemData.MAX_DEPTH);
      }

      return owned.isEmpty() ? EMPTY : new State(owned);
    }

    public CompoundTag get(ResourceLocation definitionId) {
      Objects.requireNonNull(definitionId, "definitionId");

      return entries.getCompound(definitionId.toString()).copy();
    }

    public boolean isEmpty() {
      return entries.isEmpty();
    }

    public CompoundTag save() {
      return entries.copy();
    }

    public State with(ResourceLocation definitionId, Update update) {
      Objects.requireNonNull(definitionId, "definitionId");
      String key = definitionId.toString();
      if (key.length() > 256) {
        throw new IllegalArgumentException("Upgrade bus definition ID is too long");
      }
      var value = Objects.requireNonNull(update, "update").data();
      if (get(definitionId).equals(value) && (!value.isEmpty() || !entries.contains(key))) {
        return this;
      }
      var next = entries.copy();
      if (value.isEmpty()) {
        next.remove(key);
      } else {
        next.put(key, value);
      }
      if (next.size() > MAX_ENTRIES) {
        throw new IllegalArgumentException("Too many upgrade bus data entries");
      }
      FactoryUpgradeDataSnapshots.validate(next, MAX_BYTES, MAX_TAGS, FactoryUpgradeItemData.MAX_DEPTH + 1);

      return next.isEmpty() ? EMPTY : new State(next);
    }
  }

  private static final FactoryUpgradeHandlers<Preparer> HANDLERS = new FactoryUpgradeHandlers<>();

  private UpgradeBusDataHandlers() {
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

    return host == null ? FactoryUpgradeKubeJSHandlers.startBusData(id) : host;
  }

  static Update prepare(Subscription subscription, FactoryUpgradeItemData.Context startup, State state) {
    FactoryUpgradeHandlers.requireServer();
    var preparer = preparer(subscription.handlerId());
    if (preparer == null) {
      throw new IllegalStateException("Missing upgrade bus data handler: " + subscription.handlerId());
    }
    var context = new Context(
        startup,
        subscription.definitionId(),
        subscription.busPosition(),
        subscription.upgrades());
    var result = Objects
        .requireNonNull(preparer.prepare(context, state.get(subscription.definitionId())), "Upgrade bus data result");
    if (FactoryUpgradeService.snapshot().epoch() != startup.epoch()) {
      throw new IllegalStateException("Upgrade bus data result lost its definition epoch");
    }

    return result;
  }

  static void clearOnServerStop() {
    HANDLERS.clear();
  }

  public static void validateSources(ResourceLocation definitionId, BlockPos busPosition, List<FactoryUpgradeEvents.Upgrade> upgrades) {
    Objects.requireNonNull(definitionId, "definitionId");
    Objects.requireNonNull(busPosition, "busPosition");
    Objects.requireNonNull(upgrades, "upgrades");
    if (upgrades.isEmpty()
        || upgrades.size() > FactoryUpgradeCatalog.MAX_SLOTS
        || upgrades.stream().anyMatch(
            upgrade -> upgrade == null
                || !upgrade.definitionId().equals(definitionId)
                || !upgrade.busPosition().equals(busPosition))) {
      throw new IllegalArgumentException("Invalid grouped upgrade bus data sources");
    }
  }
}
