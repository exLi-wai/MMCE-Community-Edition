package com.lw.modularmachine.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import com.lw.modularmachine.network.MachineColorPayloads;
import net.minecraft.server.level.ServerLevel;

/** Persisted casing tint shared by machine structure components. */
public interface MachineColorable {
  String COLOR_TAG = "machine_color";

  int machineColor();

  void setMachineColor(int color);

  static void save(BlockEntity entity, CompoundTag tag, int color) {
    tag.putInt(COLOR_TAG, color & 0xFFFFFF);
  }

  static int load(CompoundTag tag) {
    int color = tag.contains(COLOR_TAG) ? tag.getInt(COLOR_TAG) : 0xFFFFFF;
    return color & 0xFFFFFF;
  }

  static void update(BlockEntity entity) {
    entity.setChanged();
    Level level = entity.getLevel();
    if (level instanceof ServerLevel server) {
      BlockPos pos = entity.getBlockPos();
      MachineColorPayloads.publish(server, pos, ((MachineColorable) entity).machineColor());
    } else if (level != null) {
      BlockPos pos = entity.getBlockPos();
      level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
    }
  }
}
