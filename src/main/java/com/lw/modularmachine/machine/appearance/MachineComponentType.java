package com.lw.modularmachine.machine.appearance;

import com.lw.modularmachine.block.FactoryControllerBlock;
import com.lw.modularmachine.block.MachineCasingBlock;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.block.MachinePortBlock;
import com.lw.modularmachine.block.ParallelControllerBlock;
import com.lw.modularmachine.block.SmartInterfaceBlock;
import com.lw.modularmachine.block.UpgradeBusBlock;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.ApiStatus;

/** Internal, explicit model-hiding scope; unrelated to resource selectors. */
@ApiStatus.Internal
public enum MachineComponentType implements StringRepresentable {
  CONTROLLER("controller"),
  CASING("casing"),
  ITEM_BUS("item_bus"),
  FLUID_HATCH("fluid_hatch"),
  ENERGY_HATCH("energy_hatch"),
  PARALLEL_CONTROLLER("parallel_controller"),
  UPGRADE_BUS("upgrade_bus"),
  SMART_INTERFACE("smart_interface");

  private final String name;

  MachineComponentType(String name) {
    this.name = name;
  }

  @Override
  public String getSerializedName() {
    return name;
  }

  public static MachineComponentType forBlock(Block block) {
    if (block instanceof MachineControllerBlock || block instanceof FactoryControllerBlock) {
      return CONTROLLER;
    }
    if (block instanceof MachineCasingBlock) {
      return CASING;
    }
    if (block instanceof MachinePortBlock port) {
      return switch (port.kind().resource()) {
        case ITEM -> ITEM_BUS;
        case FLUID -> FLUID_HATCH;
        case ENERGY -> ENERGY_HATCH;
      };
    }
    if (block instanceof ParallelControllerBlock) {
      return PARALLEL_CONTROLLER;
    }
    if (block instanceof UpgradeBusBlock) {
      return UPGRADE_BUS;
    }

    return block instanceof SmartInterfaceBlock ? SMART_INTERFACE : null;
  }
}
