package com.lw.modularmachine.block;

import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.machine.HatchTier;
import com.lw.modularmachine.machine.PortKind;
import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.fluids.FluidUtil;

public final class MachinePortBlock extends BaseEntityBlock {
  private final PortKind kind;
  private final HatchTier tier;
  private final MapCodec<MachinePortBlock> codec;

  public MachinePortBlock(Properties properties, PortKind kind, HatchTier tier) {
    super(properties);
    this.kind = kind;
    this.tier = tier;
    this.codec = simpleCodec(properties1 -> new MachinePortBlock(properties1, kind, tier));
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
    return new MachinePortBlockEntity(pos, state);
  }

  public PortKind kind() {
    return kind;
  }

  public HatchTier tier() {
    return tier;
  }

  @Override
  public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
    switch (kind.resource()) {
      case ITEM -> {
        tooltip.add(
            Component.translatable(
                tier.capacity() == 1 ? "tooltip.mmcece.item_bus.slot" : "tooltip.mmcece.item_bus.slots",
                tier.capacity()).withStyle(ChatFormatting.GRAY));
        if (kind.isInput()) {
          tooltip.add(Component.translatable("tooltip.mmcece.input_group").withStyle(ChatFormatting.GRAY));
        }
      }
      case FLUID -> {
        tooltip.add(
            Component.translatable("tooltip.mmcece.fluid_hatch.capacity", tier.capacity())
                .withStyle(ChatFormatting.GRAY));
        if (kind.isInput()) {
          tooltip.add(Component.translatable("tooltip.mmcece.input_group").withStyle(ChatFormatting.GRAY));
        }
      }
      case ENERGY -> {
        tooltip.add(
            Component.translatable("tooltip.mmcece.energy_hatch.capacity", tier.capacity())
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(
            Component.translatable(
                kind.isInput() ? "tooltip.mmcece.energy_hatch.input" : "tooltip.mmcece.energy_hatch.output",
                tier.transferLimit()).withStyle(ChatFormatting.GRAY));
      }
    }
  }

  @Override
  protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
    if (!level.isClientSide && level.getBlockEntity(pos) instanceof MachinePortBlockEntity port) {
      if (port.schemaSupported() && kind.resource() == PortKind.Resource.ITEM) {
        ((ServerPlayer) player).openMenu(port, pos);
      } else if (port.schemaSupported()) {
        ((ServerPlayer) player).openMenu(port, pos);
      } else {
        player.displayClientMessage(port.statusMessage(), false);
      }
    }
    return InteractionResult.sidedSuccess(level.isClientSide);
  }

  @Override
  protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
    if (!player.isSecondaryUseActive()
        && kind.resource() == PortKind.Resource.FLUID
        && FluidUtil.getFluidHandler(stack).isPresent()) {
      if (level.isClientSide) {
        return ItemInteractionResult.SUCCESS;
      }
      if (level.getBlockEntity(pos) instanceof MachinePortBlockEntity port
          && port.schemaSupported()
          && FluidUtil.interactWithFluidHandler(player, hand, port.fluidTank())) {
        return ItemInteractionResult.CONSUME;
      }
    }
    return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
  }

  @Override
  protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
    List<ItemStack> drops = super.getDrops(state, params);
    if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof MachinePortBlockEntity port) {
      for (ItemStack stack : drops) {
        if (stack.is(asItem())) {
          BlockItem
              .setBlockEntityData(stack, port.getType(), port.saveWithoutMetadata(params.getLevel().registryAccess()));
        }
      }
    }
    return drops;
  }
}
