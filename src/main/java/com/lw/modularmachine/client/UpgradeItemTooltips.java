package com.lw.modularmachine.client;

import com.lw.modularmachine.network.UpgradeDescriptionPayloads;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * One currently inspected item, refreshed at most once per second without
 * running server scripts on the client.
 */
@EventBusSubscriber(modid = "mmcece", value = Dist.CLIENT)
public final class UpgradeItemTooltips {
  private static ItemStack inspected = ItemStack.EMPTY;
  private static List<String> lines = List.of();
  private static long sequence, sentAt = Long.MIN_VALUE;
  static {
    UpgradeDescriptionPayloads.setReceiver(UpgradeItemTooltips::accept);
  }

  private UpgradeItemTooltips() {
  }

  private static void accept(UpgradeDescriptionPayloads.Response response) {
    if (response.sequence() == sequence) {
      lines = response.lines();
    }
  }

  @SubscribeEvent
  public static void tooltip(ItemTooltipEvent event) {
    var client = Minecraft.getInstance();
    if (client.level == null || client.getConnection() == null || event.getItemStack().isEmpty()) {
      return;
    }
    long now = client.level.getGameTime();
    if (!ItemStack.isSameItemSameComponents(inspected, event.getItemStack())
        || inspected.getCount() != event.getItemStack().getCount()) {
      inspected = event.getItemStack().copy();
      lines = List.of();
      sequence++;
    }
    if (sentAt == Long.MIN_VALUE || now < sentAt || now - sentAt >= 20) {
      sentAt = now;
      sequence++;
      PacketDistributor.sendToServer(new UpgradeDescriptionPayloads.Request(sequence, inspected));
    }
    for (var line : lines) {
      event.getToolTip().add(Component.translatable(line));
    }
  }

  @SubscribeEvent
  public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
    inspected = ItemStack.EMPTY;
    lines = List.of();
    sentAt = Long.MIN_VALUE;
    sequence++;
  }
}
