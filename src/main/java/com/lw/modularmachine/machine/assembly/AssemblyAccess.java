package com.lw.modularmachine.machine.assembly;

import com.lw.modularmachine.block.FactoryControllerBlock;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.machine.structure.StructureService;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;

/** Shared player eligibility and registered/blueprint machine selection. */
@ApiStatus.Internal
final class AssemblyAccess {
  private AssemblyAccess() {
  }

  /** Legacy assembly selects the slotted blueprint, then the dedicated parent. */
  static ResourceLocation selectedMachine(StructureService.StructureSubject owner, boolean build) {
    ResourceLocation blueprint = null;
    if (owner instanceof MachineControllerBlockEntity controller) {
      blueprint = controller.blueprintMachineId();
    } else if (owner instanceof FactoryControllerBlockEntity factory) {
      blueprint = factory.blueprintInventory().machine();
    }
    if (blueprint != null) {
      return blueprint;
    }
    var block = owner.structureLevel().getBlockState(owner.structurePosition()).getBlock();
    if (block instanceof MachineControllerBlock controller && controller.dedicated() != null) {
      return controller.dedicated().machine();
    }
    if (block instanceof FactoryControllerBlock factory && factory.dedicated() != null) {
      return factory.dedicated().machine();
    }
    return build ? null : owner.structureMachineId();
  }

  static boolean validPlayer(ServerPlayer player) {
    return player.isAlive() && !player.isSpectator() && player.getAbilities().mayBuild;
  }

}
