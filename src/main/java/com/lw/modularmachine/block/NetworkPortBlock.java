package com.lw.modularmachine.block;

import com.lw.modularmachine.blockentity.NetworkPortBlockEntity;
import com.lw.modularmachine.machine.HatchTier;
import com.lw.modularmachine.machine.PortKind;
import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Network-fed physical buffers; the block does not link to an external network
 * API.
 */
public final class NetworkPortBlock extends MachinePortBlock {
  private final MapCodec<NetworkPortBlock> codec;

  public NetworkPortBlock(Properties properties, PortKind kind) {
    super(properties, kind, new HatchTier("network", kind.isInput() ? 9 : 36, 0));
    if (kind.resource() == PortKind.Resource.ENERGY) {
      throw new IllegalArgumentException("Network ports require items, fluids or chemicals");
    }
    codec = simpleCodec(settings -> new NetworkPortBlock(settings, kind));
  }

  @Override
  protected MapCodec<? extends BaseEntityBlock> codec() {
    return codec;
  }

  @Override
  public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
    return new NetworkPortBlockEntity(pos, state);
  }

  @Override
  public int itemSlotLimit() {
    return Integer.MAX_VALUE;
  }

  @Override
  public int fluidCapacity() {
    return kind().resource() == PortKind.Resource.FLUID ? Integer.MAX_VALUE : 0;
  }

  @Override
  public long chemicalCapacity() {
    return kind().resource() == PortKind.Resource.CHEMICAL ? Integer.MAX_VALUE : 0;
  }

  @Override
  public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
    tooltip.add(Component.translatable("tooltip.mmcece.network_port", kind().isInput() ? 9 : 36, Integer.MAX_VALUE));
    if (kind().isInput()) {
      tooltip.add(Component.translatable("tooltip.mmcece.input_group"));
    }
  }

  @Override
  public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
    super.setPlacedBy(level, pos, state, placer, stack);
    if (!level.isClientSide
        && placer instanceof Player player
        && level.getBlockEntity(pos) instanceof NetworkPortBlockEntity port) {
      port.connection().setOwningPlayer(player);
    }
  }

  @Override
  protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
    return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
  }

  @Override
  protected boolean dropContentsOnRemoval() {
    return false;
  }

  @Override
  protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
    var drops = super.getDrops(state, params);
    if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof NetworkPortBlockEntity port) {
      for (var stack : drops) {
        if (stack.is(asItem())) {
          BlockItem.setBlockEntityData(stack, port.getType(), port.portableData(params.getLevel().registryAccess()));
        }
      }
    }
    return drops;
  }
}
