package com.lw.modularmachine.block;

import com.lw.modularmachine.api.component.ResourceType;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.HatchTier;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.chemical.ChemicalPlatform;
import com.lw.modularmachine.util.minecraft.energy.EnergyDisplay;
import com.mojang.serialization.MapCodec;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
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

public class MachinePortBlock extends BaseEntityBlock {
  private static final Map<ResourceType, BiConsumer<MachinePortBlock, List<Component>>> TOOLTIPS = Map.of(
      ResourceType.ITEM,
      MachinePortBlock::appendItemTooltip,
      ResourceType.FLUID,
      MachinePortBlock::appendFluidTooltip,
      ResourceType.ENERGY,
      MachinePortBlock::appendEnergyTooltip,
      ResourceType.CHEMICAL,
      MachinePortBlock::appendChemicalTooltip);
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

  public int itemSlots() {
    return kind.resource() == ResourceType.ITEM ? (int) tier.capacity() : 0;
  }

  public int itemSlotLimit() {
    return 64;
  }

  public int fluidCapacity() {
    return kind.resource() == ResourceType.FLUID ? (int) MachineConfig.fluidHatchCapacity(tier) : 0;
  }

  public long chemicalCapacity() {
    return kind.resource() == ResourceType.CHEMICAL ? MachineConfig.chemicalHatchCapacity(tier) : 0;
  }

  @Override
  public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
    var writer = TOOLTIPS.get(kind.resource());
    if (writer == null) {
      tooltip.add(Component.literal(kind.resource().id().toString()).withStyle(ChatFormatting.GRAY));
      return;
    }
    writer.accept(this, tooltip);
  }

  private void appendItemTooltip(List<Component> tooltip) {
    tooltip.add(
        Component.translatable(
            tier.capacity() == 1 ? "tooltip.mmcece.item_bus.slot" : "tooltip.mmcece.item_bus.slots",
            tier.capacity()).withStyle(ChatFormatting.GRAY));
    appendInputGroupTooltip(tooltip);
  }

  private void appendFluidTooltip(List<Component> tooltip) {
    tooltip.add(
        Component.translatable("tooltip.mmcece.fluid_hatch.capacity", tier.capacity()).withStyle(ChatFormatting.GRAY));
    appendInputGroupTooltip(tooltip);
  }

  private void appendEnergyTooltip(List<Component> tooltip) {
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
              (kind.isInput() ? "tooltip.mmcece.energy_hatch.input" : "tooltip.mmcece.energy_hatch.output") + suffix,
              EnergyDisplay.format(transfer, unit),
              unit.component()).withStyle(ChatFormatting.GRAY));
    }
  }

  private void appendChemicalTooltip(List<Component> tooltip) {
    tooltip.add(
        Component.translatable("tooltip.mmcece.chemical_hatch.capacity", MachineConfig.chemicalHatchCapacity(tier))
            .withStyle(ChatFormatting.GRAY));
    appendInputGroupTooltip(tooltip);
  }

  private void appendInputGroupTooltip(List<Component> tooltip) {
    if (kind.isInput()) {
      tooltip.add(Component.translatable("tooltip.mmcece.input_group").withStyle(ChatFormatting.GRAY));
    }
  }

  @Override
  protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
    if (!level.isClientSide && level.getBlockEntity(pos) instanceof MachinePortBlockEntity port) {
      if (port.schemaSupported() && kind.resource() == ResourceType.ITEM) {
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
        && kind.resource() == ResourceType.CHEMICAL
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
        && kind.resource() == ResourceType.FLUID
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
        && dropContentsOnRemoval()
        && kind.resource() == ResourceType.ITEM
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

  protected boolean dropContentsOnRemoval() {
    return true;
  }

}
