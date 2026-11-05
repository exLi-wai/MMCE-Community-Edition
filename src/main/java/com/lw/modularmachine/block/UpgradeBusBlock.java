package com.lw.modularmachine.block;

import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import com.lw.modularmachine.machine.UpgradeBusTier;
import com.lw.modularmachine.registry.MachineRegistries;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.TooltipFlag;
import java.util.List;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class UpgradeBusBlock extends BaseEntityBlock {
  private final UpgradeBusTier tier;
  private final MapCodec<UpgradeBusBlock> codec;

  public UpgradeBusBlock(Properties properties, UpgradeBusTier tier) {
    super(properties);
    this.tier = tier;
    codec = simpleCodec(properties1 -> new UpgradeBusBlock(properties1, tier));
  }

  public UpgradeBusTier tier() {
    return tier;
  }

  @Override
  public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
    tooltip.add(Component.translatable("tooltip.mmcece.upgrade_bus", tier.slots()).withStyle(ChatFormatting.GRAY));
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
  public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
    return new UpgradeBusBlockEntity(pos, state);
  }

  @Override
  public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
    if (level.isClientSide) {
      return null;
    }

    return createTickerHelper(
        type,
        MachineRegistries.UPGRADE_BUS_ENTITY.get(),
        (world, pos, blockState, bus) -> bus.serverTick());
  }

  @Override
  protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
    if (!level.isClientSide
        && level.getBlockEntity(pos) instanceof MenuProvider provider
        && player instanceof ServerPlayer serverPlayer) {
      serverPlayer.openMenu(provider, pos);
    }
    return InteractionResult.sidedSuccess(level.isClientSide);
  }
}
