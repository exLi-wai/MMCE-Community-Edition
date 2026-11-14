package com.lw.modularmachine.item;

import com.lw.modularmachine.machine.selection.RangedConstructToolActions;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * Main-hand selection interactions. Preview is client-only; export remains
 * disconnected.
 */
public final class RangedConstructToolItem extends Item {
  public RangedConstructToolItem(Properties properties) {
    super(properties);
  }

  @Override
  public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
    return new InteractionResultHolder<>(interact(player, hand, null), player.getItemInHand(hand));
  }

  @Override
  public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
    return useOn(context);
  }

  @Override
  public InteractionResult useOn(UseOnContext context) {
    return interact(context.getPlayer(), context.getHand(), context.getClickedPos());
  }

  private static InteractionResult interact(Player player, InteractionHand hand, BlockPos position) {
    if (player == null || hand != InteractionHand.MAIN_HAND) {
      return InteractionResult.PASS;
    }
    if (player.level().isClientSide) {
      RangedConstructToolActions.useClient(position);
    }

    return InteractionResult.sidedSuccess(player.level().isClientSide);
  }

  @Override
  public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
    lines.add(Component.translatable("tooltip.mmcece.ranged_construct_tool"));
  }
}
