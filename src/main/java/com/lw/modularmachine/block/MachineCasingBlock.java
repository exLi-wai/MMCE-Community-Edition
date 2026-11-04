package com.lw.modularmachine.block;

import com.lw.modularmachine.blockentity.MachineCasingBlockEntity;
import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

public final class MachineCasingBlock extends BaseEntityBlock {
  public static final MapCodec<MachineCasingBlock> CODEC = simpleCodec(MachineCasingBlock::new);

  public MachineCasingBlock(Properties properties) {
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
    return new MachineCasingBlockEntity(pos, state);
  }

  @Override
  protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
    List<ItemStack> drops = super.getDrops(state, params);
    if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof MachineCasingBlockEntity casing) {
      for (ItemStack stack : drops) {
        if (stack.is(asItem())) {
          BlockItem.setBlockEntityData(
              stack,
              casing.getType(),
              casing.saveWithoutMetadata(params.getLevel().registryAccess()));
        }
      }
    }
    return drops;
  }
}
