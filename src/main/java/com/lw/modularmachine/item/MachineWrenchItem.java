package com.lw.modularmachine.item;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * Right-click behavior has one authoritative entry in WrenchInteractions,
 * including sneak-use.
 */
public final class MachineWrenchItem extends Item {
  public MachineWrenchItem(Properties properties) {
    super(properties);
  }

  @Override
  public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
    tooltip.add(Component.translatable("tooltip.mmcece.machine_wrench").withStyle(ChatFormatting.WHITE));
  }
}
