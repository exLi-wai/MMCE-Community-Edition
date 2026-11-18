package com.lw.modularmachine.block;

import com.lw.modularmachine.blockentity.EmberInputHatchBlockEntity;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.EmberHatchTier;
import com.mojang.serialization.MapCodec;
import com.rekindled.embers.util.DecimalFormats;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class EmberInputHatchBlock extends MachineCasingBlock {
  private final MapCodec<EmberInputHatchBlock> codec;
  private final EmberHatchTier tier;

  public EmberInputHatchBlock(Properties properties, EmberHatchTier tier) {
    super(properties);
    this.tier = tier;
    codec = simpleCodec(next -> new EmberInputHatchBlock(next, tier));
  }

  @Override
  protected MapCodec<EmberInputHatchBlock> codec() {
    return codec;
  }

  @Override
  public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
    return new EmberInputHatchBlockEntity(pos, state);
  }

  @Override
  public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
    var data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
    long stored = data == null ? 0 : data.copyTag().getLong(EmberInputHatchBlockEntity.STORED_AMOUNT_TAG);
    long amount = Math.max(0, stored);
    var format = DecimalFormats.getDecimalFormat("embers.decimal_format.ember");
    tooltip.add(
        Component.translatable(
            "embers.tooltip.item.ember",
            format.format(amount / 1_000_000.0),
            format.format(MachineConfig.emberInputCapacity(tier))).withStyle(ChatFormatting.GRAY));
  }

  public EmberHatchTier tier() {
    return tier;
  }
}
