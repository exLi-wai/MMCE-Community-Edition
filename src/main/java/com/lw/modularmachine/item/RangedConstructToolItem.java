package com.lw.modularmachine.item;

import com.lw.modularmachine.machine.selection.RangedSelectionService;
import com.lw.modularmachine.registry.MachineDataComponents;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
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
 * Existing ranged tool: open the editor, pick corners, then use a controller to
 * export.
 */
public final class RangedConstructToolItem extends Item {
  public RangedConstructToolItem(Properties properties) {
    super(properties);
  }

  @Override
  public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
    var stack = player.getItemInHand(hand);
    if (hand != InteractionHand.MAIN_HAND) {
      return InteractionResultHolder.pass(stack);
    }
    if (player instanceof ServerPlayer server) {
      RangedSelectionService.open(server);
    }
    return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
  }

  @Override
  public InteractionResult useOn(UseOnContext context) {
    var player = context.getPlayer();
    if (player == null) {
      return InteractionResult.PASS;
    }
    return interact(context.getLevel(), player, context.getItemInHand(), context.getClickedPos(), context.getHand());
  }

  /**
   * Block events intercept controller GUIs before useOn; both routes share this
   * dispatcher.
   */
  public static InteractionResult interact(Level level, Player player, ItemStack stack, BlockPos pos, InteractionHand hand) {
    if (hand != InteractionHand.MAIN_HAND || !(stack.getItem() instanceof RangedConstructToolItem)) {
      return InteractionResult.PASS;
    }
    if (level.isClientSide) {
      return InteractionResult.SUCCESS;
    }
    return player instanceof ServerPlayer server ? RangedSelectionService
        .interact(server, pos.immutable()) : InteractionResult.PASS;
  }

  @Override
  public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
    lines.add(Component.translatable("tooltip.mmcece.ranged_construct_tool"));
    var first = stack.get(MachineDataComponents.SELECTION_FIRST.get());
    if (first != null) {
      lines.add(Component.translatable("tooltip.mmcece.ranged_construct_tool.first", coordinates(first)));
    }
    var second = stack.get(MachineDataComponents.SELECTION_SECOND.get());
    if (second != null) {
      lines.add(Component.translatable("tooltip.mmcece.ranged_construct_tool.second", coordinates(second)));
    }
  }

  private static Component coordinates(BlockPos pos) {
    return Component.literal(pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
  }
}
