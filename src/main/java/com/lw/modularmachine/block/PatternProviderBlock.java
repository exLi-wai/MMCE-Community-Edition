package com.lw.modularmachine.block;

import com.lw.modularmachine.blockentity.PatternProviderBlockEntity;
import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public final class PatternProviderBlock extends BaseEntityBlock {
  public static final MapCodec<PatternProviderBlock> CODEC = simpleCodec(PatternProviderBlock::new);
  public PatternProviderBlock(Properties properties) {
    super(properties);
  }
  @Override
  protected MapCodec<? extends BaseEntityBlock> codec() {
    return CODEC;
  }
  @Override
  protected RenderShape getRenderShape(BlockState state) {
    return RenderShape.MODEL;
  }
  @Override
  public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
    return new PatternProviderBlockEntity(pos, state);
  }
  @Override
  protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
    if (player instanceof ServerPlayer server
        && level.getBlockEntity(pos) instanceof PatternProviderBlockEntity provider
        && provider.schemaSupported()) {
      server.openMenu(provider, pos);
    }
    return InteractionResult.sidedSuccess(level.isClientSide);
  }
  @Override
  public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
    super.setPlacedBy(level, pos, state, placer, stack);
    if (!level.isClientSide
        && placer instanceof Player player
        && level.getBlockEntity(pos) instanceof PatternProviderBlockEntity provider) {
      provider.connection().setOwningPlayer(player);
    }
  }
  @Override
  protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
    var drops = super.getDrops(state, params);
    if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof PatternProviderBlockEntity provider) {
      for (var stack : drops) {
        if (stack.is(asItem())) {
          BlockItem
              .setBlockEntityData(stack, provider.getType(), provider.portableData(params.getLevel().registryAccess()));
        }
      }
    }
    return drops;
  }
  @Override
  public ItemStack getCloneItemStack(BlockState state, HitResult hit, LevelReader level, BlockPos pos, Player player) {
    return new ItemStack(this);
  }
}
