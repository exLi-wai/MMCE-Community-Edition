package com.lw.modularmachine.block;

import com.lw.modularmachine.blockentity.ParallelControllerBlockEntity;
import com.lw.modularmachine.machine.ParallelControllerTier;
import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class ParallelControllerBlock extends BaseEntityBlock {
  private final ParallelControllerTier tier;
  private final MapCodec<ParallelControllerBlock> codec;

  public ParallelControllerBlock(Properties properties, ParallelControllerTier tier) {
    super(properties);
    this.tier = tier;
    codec = simpleCodec(settings -> new ParallelControllerBlock(settings, tier));
  }

  public ParallelControllerTier tier() {
    return tier;
  }

  @Override
  public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
    String key = "tooltip.mmcece.parallel_controller" + (tier.configurable() ? "" : ".fixed");
    tooltip.add(Component.translatable(key, tier.maximum()).withStyle(ChatFormatting.GRAY));
    if (tier == ParallelControllerTier.PIG) {
      tooltip.add(
          Component.translatable("tooltip.mmcece.parallel_controller.pig.description").withStyle(ChatFormatting.GRAY));
      tooltip.add(
          Component.translatable("tooltip.mmcece.parallel_controller.pig.technology").withStyle(ChatFormatting.BLACK));
    }
  }

  @Override
  protected MapCodec<? extends BaseEntityBlock> codec() {
    return codec;
  }

  @Override
  protected RenderShape getRenderShape(BlockState state) {
    return RenderShape.MODEL;
  }

  @Override
  public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
    return new ParallelControllerBlockEntity(position, state);
  }

  @Override
  protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit) {
    if (tier.configurable()
        && player instanceof ServerPlayer serverPlayer
        && level.getBlockEntity(position) instanceof ParallelControllerBlockEntity parallel
        && parallel.schemaSupported()) {
      serverPlayer.openMenu(parallel, position);
    }

    return InteractionResult.sidedSuccess(level.isClientSide);
  }

}
