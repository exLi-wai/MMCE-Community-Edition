package com.lw.modularmachine.blockentity;

import com.lw.modularmachine.block.EmberInputHatchBlock;
import com.lw.modularmachine.registry.MachineRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class EmberInputHatchBlockEntity extends BufferedRecipeHatchBlockEntity {
  public static final String STORED_AMOUNT_TAG = "ember_micros";

  public EmberInputHatchBlockEntity(BlockPos position, BlockState state) {
    super(MachineRegistries.EMBER_INPUT_ENTITY.get(), position, state,
        ((EmberInputHatchBlock) state.getBlock()).tier().capacity() * 1_000_000L, STORED_AMOUNT_TAG);
  }

  public boolean acceptsVolatile() {
    return ((EmberInputHatchBlock) getBlockState().getBlock()).tier().acceptsVolatile();
  }
}
