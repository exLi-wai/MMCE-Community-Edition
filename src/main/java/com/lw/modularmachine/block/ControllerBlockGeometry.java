package com.lw.modularmachine.block;

import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.machine.structure.VerificationState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

final class ControllerBlockGeometry {
  private ControllerBlockGeometry() {
  }

  static VoxelShape shape(BlockGetter level, BlockPos pos) {
    BlockEntity entity = level.getBlockEntity(pos);
    MachineDefinition definition = null;
    if (entity instanceof MachineControllerBlockEntity controller
        && controller.verificationState() == VerificationState.CONFIRMED) {
      definition = StructureService.definition(controller.machineId());
    } else if (entity instanceof FactoryControllerBlockEntity factory
        && factory.verificationState() == VerificationState.CONFIRMED) {
      definition = StructureService.definition(factory.machineId());
    }
    if (definition == null) {
      return Shapes.block();
    }
    AABB box = definition.controllerBoundingBox();
    return Shapes.box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
  }
}
