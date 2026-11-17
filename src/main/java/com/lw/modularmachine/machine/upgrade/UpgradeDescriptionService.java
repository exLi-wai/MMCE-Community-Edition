package com.lw.modularmachine.machine.upgrade;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.upgrade.UpgradeDescriptions;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Server-authored presentation snapshots; no tooltip callback receives a live
 * bus or stack.
 */
@ApiStatus.Internal
public final class UpgradeDescriptionService {
  public static final int MAX_BUS_LINES = 128;
  private record Cached(long epoch, long revision, long bindingRevision, List<String> lines) {
  }
  private static final Map<UpgradeBusBlockEntity, Cached> BUS = new WeakHashMap<>();
  private static final Set<ResourceLocation> REPORTED = new HashSet<>();
  private static long reportedEpoch = -1;

  private UpgradeDescriptionService() {
  }

  public static List<String> bus(UpgradeBusBlockEntity bus) {
    var catalog = UpgradeCatalogService.snapshot();
    var cached = BUS.get(bus);
    if (cached != null
        && cached.epoch() == catalog.epoch()
        && cached.revision() == bus.upgradeRevision()
        && cached.bindingRevision() == bus.bindingRevision()) {
      return cached.lines();
    }
    var lines = new ArrayList<String>();
    for (int slot = 0; slot < bus.slotCount() && lines.size() < MAX_BUS_LINES; slot++) {
      var stack = bus.inventory().getStackInSlot(slot).copy();
      var next = describe(stack, true, bus.boundMachine().orElse(null), bus.getBlockPos());
      lines.addAll(next.subList(0, Math.min(next.size(), MAX_BUS_LINES - lines.size())));
    }
    var result = List.copyOf(lines);
    BUS.put(bus, new Cached(catalog.epoch(), bus.upgradeRevision(), bus.bindingRevision(), result));
    return result;
  }

  public static List<String> describe(ItemStack stack, boolean bus, ResourceLocation machine, BlockPos position) {
    var server = UpgradeHandlerRegistry.requireServer();
    var catalog = UpgradeCatalogService.snapshot();
    var definition = catalog.match(stack).orElse(null);
    if (definition == null) {
      return List.of();
    }
    var registered = catalog.types().get(definition.effect().id());
    if (registered == null) {
      return List.of();
    }
    var lines = new ArrayList<String>();
    lines.add(registered.type().localizedName());
    var handler = bus ? registered.busDescription() : registered.description();
    if (handler == null) {
      lines.addAll(registered.type().descriptions());
    } else {
      try {
        var encoded = stack.save(server.registryAccess());
        if (!(encoded instanceof CompoundTag data)) {
          return List.of();
        }
        UpgradeDataSnapshots.validate(data, 65_536, 4096, 32);
        var context = new UpgradeDescriptions.Context(
            registered.type(),
            definition.item(),
            stack.getCount(),
            data,
            machine,
            position);
        UpgradeCallbackGuard.enterDescription();
        try {
          lines.addAll(UpgradeDescriptions.validate(handler.describe(context)));
        } finally {
          UpgradeCallbackGuard.exitDescription();
        }
      } catch (RuntimeException invalid) {
        if (reportedEpoch != catalog.epoch()) {
          REPORTED.clear();
          reportedEpoch = catalog.epoch();
        }
        if (REPORTED.add(definition.effect().id())) {
          ModularMachine.LOGGER.warn("Invalid upgrade description: {}", definition.effect().id(), invalid);
        }
      }
    }
    if (UpgradeCatalogService.snapshot() != catalog) {
      return List.of();
    }
    return List.copyOf(lines.subList(0, Math.min(lines.size(), UpgradeDescriptions.MAX_LINES)));
  }

  static void clear() {
    BUS.clear();
    REPORTED.clear();
    reportedEpoch = -1;
  }
}
