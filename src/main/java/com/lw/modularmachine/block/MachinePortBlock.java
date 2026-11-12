package com.lw.modularmachine.block;

import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.HatchTier;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.chemical.ChemicalPlatform;
import com.lw.modularmachine.util.minecraft.energy.EnergyDisplay;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
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
        long capacity = MachineConfig.energyHatchCapacity(tier);
        long transfer = MachineConfig.energyHatchTransfer(tier);
        for (var unit : EnergyDisplay.hatchTooltipUnits()) {
          String suffix = unit.converted() ? "_converted" : "_unit";
          tooltip.add(
              Component.translatable(
                  "tooltip.mmcece.energy_hatch.capacity" + suffix,
                  EnergyDisplay.format(capacity, unit),
                  unit.component()).withStyle(ChatFormatting.GRAY));
          tooltip.add(
              Component.translatable(
                  (kind
                      .isInput() ? "tooltip.mmcece.energy_hatch.input" : "tooltip.mmcece.energy_hatch.output") + suffix,
                  EnergyDisplay.format(transfer, unit),
                  unit.component()).withStyle(ChatFormatting.GRAY));
        }
      }
      case CHEMICAL -> {
        tooltip.add(
            Component.translatable("tooltip.mmcece.chemical_hatch.capacity", MachineConfig.chemicalHatchCapacity(tier))
                .withStyle(ChatFormatting.GRAY));
        if (kind.isInput()) {
          tooltip.add(Component.translatable("tooltip.mmcece.input_group").withStyle(ChatFormatting.GRAY));
        }
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
        && kind.resource() == PortKind.Resource.CHEMICAL
        && ChemicalPlatform.hasContainer(stack)) {
      if (level.isClientSide) {
        return ItemInteractionResult.SUCCESS;
      }
      if (level.getBlockEntity(pos) instanceof MachinePortBlockEntity port
          && port.schemaSupported()
          && ChemicalPlatform.interact(player, hand, port)) {
        return ItemInteractionResult.CONSUME;
      }
    }
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
  public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
    if (!state.is(newState.getBlock())
        && !isMoving
        && !level.isClientSide
        && kind.resource() == PortKind.Resource.ITEM
        && level.getBlockEntity(pos) instanceof MachinePortBlockEntity port) {
      for (int slot = 0; slot < port.inventory().getSlots(); slot++) {
        ItemStack stack = port.inventory().getStackInSlot(slot);
        if (!stack.isEmpty()) {
          popResource(level, pos, stack.copy());
          port.inventory().setStackInSlot(slot, ItemStack.EMPTY);
        }
      }
    }
    super.onRemove(state, level, pos, newState, isMoving);
  }

}
