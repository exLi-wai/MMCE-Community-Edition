package com.lw.modularmachine.blockentity;

import com.lw.modularmachine.api.component.MachineColorable;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.registry.MachineRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class MachineCasingBlockEntity extends BlockEntity implements MachineColorable {
  private int machineColor = MachineConfig.defaultCasingColor();
  private boolean machineColorAssigned;

  public MachineCasingBlockEntity(BlockPos pos, BlockState state) {
    super(MachineRegistries.CASING_ENTITY.get(), pos, state);
  }

  @Override
  public int machineColor() {
    return machineColor;
  }

  @Override
  public boolean machineColorAssigned() {
    return machineColorAssigned;
  }

  @Override
  public void setMachineColor(int color) {
    color &= 0xFFFFFF;
    boolean wasAssigned = machineColorAssigned;
    machineColorAssigned = true;
    if (!wasAssigned || machineColor != color) {
      machineColor = color;
      MachineColorable.update(this);
    }
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    MachineColorable.save(this, tag, machineColor);
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.loadAdditional(tag, registries);
    machineColorAssigned = tag.contains(MachineColorable.COLOR_TAG);
    machineColor = MachineColorable.load(tag);
  }

  @Override
  public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
    return saveWithoutMetadata(registries);
  }
}
