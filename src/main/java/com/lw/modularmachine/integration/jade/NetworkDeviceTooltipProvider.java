package com.lw.modularmachine.integration.jade;

import com.lw.modularmachine.blockentity.NetworkPortBlockEntity;
import com.lw.modularmachine.blockentity.PatternMirrorBlockEntity;
import com.lw.modularmachine.blockentity.PatternProviderBlockEntity;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.network.MachineNetworkPlatform;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/** Internal bounded, read-only network status; mirrors never own a node. */
final class NetworkDeviceTooltipProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
  static final NetworkDeviceTooltipProvider INSTANCE = new NetworkDeviceTooltipProvider();
  private static final String STATUS = "mmcece:network_device_status";
  private static final String TARGET = "mmcece:network_device_target";

  private enum Status {
    ONLINE(ChatFormatting.GREEN),
    OFFLINE(ChatFormatting.RED),
    UNAVAILABLE(ChatFormatting.RED),
    MIRROR_ONLINE(ChatFormatting.GREEN),
    MIRROR_OFFLINE(ChatFormatting.RED),
    MIRROR_UNBOUND(ChatFormatting.YELLOW),
    MIRROR_UNLOADED(ChatFormatting.YELLOW),
    MIRROR_INVALID(ChatFormatting.RED),
    MIRROR_UNAVAILABLE(ChatFormatting.RED);

    private final ChatFormatting color;

    Status(ChatFormatting color) {
      this.color = color;
    }
  }

  private NetworkDeviceTooltipProvider() {
  }

  @Override
  public ResourceLocation getUid() {
    return ResourceLocation.fromNamespaceAndPath("mmcece", "network_device_status");
  }

  @Override
  public boolean shouldRequestData(BlockAccessor accessor) {
    var entity = accessor.getBlockEntity();

    return entity instanceof NetworkPortBlockEntity
        || entity instanceof PatternProviderBlockEntity
        || entity instanceof PatternMirrorBlockEntity;
  }

  @Override
  public void appendServerData(CompoundTag data, BlockAccessor accessor) {
    var entity = accessor.getBlockEntity();
    if (entity == null || !(entity.getLevel() instanceof ServerLevel level)) {
      return;
    }
    ServerThreadGuard.require(level, "Jade network device status");
    if (entity.isRemoved()
        || !level.hasChunkAt(entity.getBlockPos())
        || level.getBlockEntity(entity.getBlockPos()) != entity) {
      return;
    }
    Status status;
    if (entity instanceof NetworkPortBlockEntity port) {
      if (!port.schemaSupported()
          || !MachineNetworkPlatform.available()
          || port.kind().resource() == PortKind.Resource.CHEMICAL && !MachineNetworkPlatform.chemicalAvailable()) {
        status = Status.UNAVAILABLE;
      } else {
        status = port.connection().active() ? Status.ONLINE : Status.OFFLINE;
      }
    } else if (entity instanceof PatternProviderBlockEntity provider) {
      if (provider.state().inTransaction()) {
        return;
      }
      status = provider.schemaSupported() ? networkStatus(provider, false) : Status.UNAVAILABLE;
    } else if (entity instanceof PatternMirrorBlockEntity mirror) {
      status = mirrorStatus(data, mirror, level);
    } else {
      return;
    }
    if (status != null) {
      data.putString(STATUS, status.name());
    }
  }

  private static Status networkStatus(PatternProviderBlockEntity provider, boolean mirror) {
    if (mirror) {
      return provider.connection().active() ? Status.MIRROR_ONLINE : Status.MIRROR_OFFLINE;
    }
    return provider.connection().active() ? Status.ONLINE : Status.OFFLINE;
  }

  private static Status mirrorStatus(CompoundTag data, PatternMirrorBlockEntity mirror, ServerLevel level) {
    if (!mirror.schemaSupported()) {
      return Status.MIRROR_UNAVAILABLE;
    }
    var target = mirror.binding().orElse(null);
    if (target == null) {
      return Status.MIRROR_UNBOUND;
    }
    data.putLong(TARGET, target.position().asLong());
    if (!target.dimension().equals(level.dimension().location())) {
      return Status.MIRROR_INVALID;
    }
    if (!level.hasChunkAt(target.position())) {
      return Status.MIRROR_UNLOADED;
    }
    // Provider identity/schema reads reject a live storage lease.
    if (level.getBlockEntity(target.position()) instanceof PatternProviderBlockEntity provider
        && provider.state().inTransaction()) {
      return null;
    }
    var provider = mirror.resolveProvider().orElse(null);
    if (provider == null) {
      return Status.MIRROR_INVALID;
    }

    return networkStatus(provider, true);
  }

  @Override
  public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
    var data = accessor.getServerData();
    if (!data.contains(STATUS, Tag.TAG_STRING)) {
      return;
    }
    Status status;
    try {
      status = Status.valueOf(data.getString(STATUS));
    } catch (IllegalArgumentException invalid) {
      return;
    }
    tooltip.add(
        Component.translatable("jade.mmcece.network." + status.name().toLowerCase(Locale.ROOT))
            .withStyle(status.color));
    if (data.contains(TARGET, Tag.TAG_LONG)) {
      tooltip.add(
          Component.translatable("jade.mmcece.network.target", BlockPos.of(data.getLong(TARGET)).toShortString())
              .withStyle(ChatFormatting.GRAY));
    }
  }
}
