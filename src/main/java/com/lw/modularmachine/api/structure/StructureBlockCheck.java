package com.lw.modularmachine.api.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Main-thread structure predicate over detached state and block-entity data.
 */
@FunctionalInterface
public interface StructureBlockCheck {
  boolean matches(Context context);

  record Context(ResourceLocation dimension, BlockPos position, BlockState state, CompoundTag blockData) {
    public Context {
      position = position.immutable();
      blockData = blockData.copy();
    }

    @Override
    public CompoundTag blockData() {
      return blockData.copy();
    }
  }
}
