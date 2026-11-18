package com.lw.modularmachine.blockentity;

import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.registry.MachineRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class LaserInputHatchBlockEntity extends BufferedRecipeHatchBlockEntity {
  public LaserInputHatchBlockEntity(BlockPos position, BlockState state) {
    super(MachineRegistries.LASER_INPUT_ENTITY.get(), position, state, MachineConfig.laserInputCapacity(),
        "laser_energy");
  }
}
