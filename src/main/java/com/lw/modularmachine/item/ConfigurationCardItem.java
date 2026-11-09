package com.lw.modularmachine.item;

import com.lw.modularmachine.machine.configuration.ConfigurationCardService;
import com.lw.modularmachine.registry.MachineDataComponents;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;

/**
 * Block interactions have one authoritative entry in ConfigurationCardService.
 */
public final class ConfigurationCardItem extends Item {
  public ConfigurationCardItem(Properties properties) {
    super(properties);
  }

  @Override
  public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
    if (player.isShiftKeyDown()) {
      if (player instanceof ServerPlayer server) {
        ConfigurationCardService.clear(server, hand);
      }
      return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }
    return InteractionResultHolder.pass(player.getItemInHand(hand));
  }

  @Override
  public boolean doesSneakBypassUse(ItemStack stack, LevelReader level, BlockPos position, Player player) {
    return true;
  }

  @Override
  public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
    tooltip.add(Component.translatable("tooltip.mmcece.configuration_card").withStyle(ChatFormatting.GRAY));
    var data = stack.get(MachineDataComponents.CONFIGURATION_CARD.get());
    if (data != null) {
      tooltip.add(
          Component
              .translatable("tooltip.mmcece.configuration_card.saved", ConfigurationCardService.typeName(data.kind()))
              .withStyle(ChatFormatting.GRAY));
      switch (data.kind()) {
        case FACTORY -> tooltip.add(
            Component.translatable("tooltip.mmcece.configuration_card.channels", data.channels().size())
                .withStyle(ChatFormatting.GRAY));
        case SMART -> tooltip.add(
            Component.translatable(
                "tooltip.mmcece.configuration_card.interface",
                data.interfaceType(),
                data.interfaceValue()).withStyle(ChatFormatting.GRAY));
        default -> tooltip.add(
            Component.translatable("tooltip.mmcece.configuration_card.value", data.number())
                .withStyle(ChatFormatting.GRAY));
      }
      data.compatibilityMachine()
          .ifPresent(machine -> tooltip.add(Component.literal(machine.toString()).withStyle(ChatFormatting.DARK_GRAY)));
    }
    tooltip.add(Component.translatable("tooltip.mmcece.configuration_card.owner").withStyle(ChatFormatting.DARK_GRAY));
  }
}
