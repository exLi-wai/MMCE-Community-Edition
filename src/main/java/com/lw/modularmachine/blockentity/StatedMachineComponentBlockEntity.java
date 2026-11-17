package com.lw.modularmachine.blockentity;

import com.lw.modularmachine.api.component.MachineColorable;
import com.lw.modularmachine.config.MachineConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public final class StatedMachineComponentBlockEntity extends BlockEntity implements MachineColorable {
  private int color = MachineConfig.defaultCasingColor();
  private boolean assigned;

  public StatedMachineComponentBlockEntity(BlockEntityType<?> type, BlockPos position, BlockState state) {
    super(type, position, state);
  }

  @Override
  public int machineColor() {
    return color;
  }

  @Override
  public boolean machineColorAssigned() {
    return assigned;
  }

  @Override
  public void setMachineColor(int value) {
    value &= 0xFFFFFF;
    if (!assigned || color != value) {
      assigned = true;
      color = value;
      MachineColorable.update(this);
    }
  }

  @Override
  protected void saveAdditional(CompoundTag data, HolderLookup.Provider registries) {
    super.saveAdditional(data, registries);
    MachineColorable.save(this, data, color);
  }

  @Override
  protected void loadAdditional(CompoundTag data, HolderLookup.Provider registries) {
    super.loadAdditional(data, registries);
    assigned = data.contains(MachineColorable.COLOR_TAG);
    color = MachineColorable.load(data);
  }

  @Override
  public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
    return saveWithoutMetadata(registries);
  }
}
