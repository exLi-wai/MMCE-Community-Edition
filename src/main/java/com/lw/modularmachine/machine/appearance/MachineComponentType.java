package com.lw.modularmachine.machine.appearance;

import com.lw.modularmachine.api.component.ResourceType;
import java.util.Map;
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
import com.lw.modularmachine.block.StatedMachineComponentBlock;

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
  SMART_INTERFACE("smart_interface"),
  CHEMICAL_HATCH("chemical_hatch");

  private final String name;
  private static final Map<ResourceType, MachineComponentType> RESOURCE_MODELS = Map.of(
      ResourceType.ITEM,
      ITEM_BUS,
      ResourceType.FLUID,
      FLUID_HATCH,
      ResourceType.ENERGY,
      ENERGY_HATCH,
      ResourceType.CHEMICAL,
      CHEMICAL_HATCH);

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
    if (block instanceof MachineCasingBlock || block instanceof StatedMachineComponentBlock) {
      return CASING;
    }
    if (block instanceof MachinePortBlock port) {
      return RESOURCE_MODELS.get(port.kind().resource());
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
