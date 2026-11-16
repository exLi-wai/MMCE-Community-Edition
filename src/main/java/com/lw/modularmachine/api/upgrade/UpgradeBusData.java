package com.lw.modularmachine.api.upgrade;

import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import com.lw.modularmachine.machine.upgrade.UpgradeBusDataHandlers;
import com.lw.modularmachine.machine.upgrade.UpgradeDataSnapshots;

/** Controlled startup bus-data proposals and registration. */
public final class UpgradeBusData {
  @FunctionalInterface
  public interface Preparer {
    Update prepare(Context context, CompoundTag currentData);
  }

  public record Update(CompoundTag data) {
    public Update {
      data = UpgradeDataSnapshots.copy(data);
    }

    @Override
    public CompoundTag data() {
      return data.copy();
    }
  }

  public record Context(
      UpgradeItemData.Context startup,
      ResourceLocation definitionId,
      BlockPos busPosition,
      List<UpgradeEvents.Upgrade> upgrades) {
    public Context {
      Objects.requireNonNull(startup, "startup");
      UpgradeBusDataHandlers.validateSources(definitionId, busPosition, upgrades);
      busPosition = busPosition.immutable();
      upgrades = List.copyOf(upgrades);
    }
  }

  private UpgradeBusData() {
  }

  public static void register(ResourceLocation id, Preparer handler) {
    UpgradeBusDataHandlers.register(id, handler);
  }

  public static void remove(ResourceLocation id) {
    UpgradeBusDataHandlers.remove(id);
  }

  public static void invalidate(ResourceLocation id) {
    UpgradeBusDataHandlers.invalidate(id);
  }

  public static boolean isRegistered(ResourceLocation id) {
    return UpgradeBusDataHandlers.isRegistered(id);
  }

}
