package com.lw.modularmachine.integration.jade;

import com.lw.modularmachine.api.controller.ControllerOwnershipApi;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.ITooltip;

/**
 * Shared server ownership snapshot and legacy controller tooltip presentation.
 */
final class ControllerOwnershipTooltip {
  private static final String OWNERSHIP = "mmcece:ownership";

  private ControllerOwnershipTooltip() {
  }

  static void appendServerData(CompoundTag data, ServerLevel level, BlockPos position) {
    data.remove(OWNERSHIP);
    ControllerOwnershipApi.inspect(level, position).ifPresent(ownership -> {
      if (!ownership.valid() || ownership.ownerId().isEmpty()) {
        return;
      }
      var owner = new CompoundTag();
      owner.putUUID("id", ownership.ownerId().orElseThrow());
      ownership.ownerName().ifPresent(name -> owner.putString("name", name));
      data.put(OWNERSHIP, owner);
    });
  }

  static void appendTooltip(ITooltip tooltip, BlockAccessor accessor) {
    var owner = accessor.getServerData().getCompound(OWNERSHIP);
    if (!owner.hasUUID("id")) {
      return;
    }
    var ownerId = owner.getUUID("id");
    var player = accessor.getPlayer();
    Component value;
    if (player != null && ownerId.equals(player.getUUID())) {
      value = Component.translatable("jade.mmcece.owner.self").withStyle(ChatFormatting.GREEN);
    } else if (!owner.getString("name").isEmpty()) {
      value = Component.literal(owner.getString("name")).withStyle(ChatFormatting.RED);
    } else {
      value = Component.translatable("jade.mmcece.owner.unknown", ownerId.toString()).withStyle(ChatFormatting.YELLOW);
    }
    tooltip.add(Component.translatable("jade.mmcece.owner", value).withStyle(ChatFormatting.AQUA));
  }
}
