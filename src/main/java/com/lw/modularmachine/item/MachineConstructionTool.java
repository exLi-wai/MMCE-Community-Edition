package com.lw.modularmachine.item;

import com.lw.modularmachine.machine.assembly.AssemblyService;
import com.lw.modularmachine.machine.structure.StructureService;
import java.util.List;
import net.minecraft.ChatFormatting;
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
import net.minecraft.world.level.LevelReader;

/**
 * Dedicated projector. Construction is handled by the configurable right-click
 * item.
 */
public final class MachineConstructionTool extends Item {
  public MachineConstructionTool(Properties properties) {
    super(properties);
  }
  @Override
  public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
    return useOn(context);
  }
  @Override
  public InteractionResult useOn(UseOnContext context) {
    if (context.getLevel().isClientSide) {
      return InteractionResult.SUCCESS;
    }
    if (context.getPlayer() instanceof ServerPlayer player) {
      if (player.isShiftKeyDown()) {
        AssemblyService.cancelProjection(player);
        return InteractionResult.CONSUME;
      }
      if (context.getLevel().hasChunkAt(context.getClickedPos())
          && context.getLevel()
              .getBlockEntity(context.getClickedPos()) instanceof StructureService.StructureSubject controller) {
        AssemblyService.request(player, controller, false, context.getHand());
      } else {
        player.displayClientMessage(Component.translatable("message.mmcece.assembly.controller"), true);
      }
    }
    return InteractionResult.CONSUME;
  }
  @Override
  public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
    if (!level.isClientSide && player instanceof ServerPlayer server && player.isShiftKeyDown()) {
      AssemblyService.cancelProjection(server);
    }
    return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
  }
  @Override
  public boolean doesSneakBypassUse(ItemStack stack, LevelReader level, BlockPos pos, Player player) {
    return true;
  }
  @Override
  public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
    tooltip.add(Component.translatable("tooltip.mmcece.machine_projector").withStyle(ChatFormatting.WHITE));
  }
}
