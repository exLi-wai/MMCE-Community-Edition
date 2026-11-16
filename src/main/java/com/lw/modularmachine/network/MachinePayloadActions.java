package com.lw.modularmachine.network;

import com.lw.modularmachine.api.component.ResourceType;
import com.lw.modularmachine.machine.chemical.ChemicalPlatform;
import com.lw.modularmachine.machine.factory.FactoryThreadConfiguration.Change;
import com.lw.modularmachine.menu.FactoryControllerMenu;
import com.lw.modularmachine.menu.MachineResourceMenu;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.PacketDistributor;
import static com.lw.modularmachine.network.MachinePayloads.FactoryThreadActionPayload;
import static com.lw.modularmachine.network.MachinePayloads.ResourcePortActionPayload;
import org.jetbrains.annotations.ApiStatus;

/**
 * Server menu actions; called only from the existing enqueued payload handlers.
 */
@ApiStatus.Internal
final class MachinePayloadActions {
  private MachinePayloadActions() {
  }

  static void handleResourceAction(ResourcePortActionPayload payload, IPayloadContext context) {
    if (!(context.player() instanceof ServerPlayer player)
        || !(player.containerMenu instanceof MachineResourceMenu menu)
        || !menu.stillValid(player)
        || !menu.port().getBlockPos().equals(payload.position())) {
      return;
    }
    if (menu.port().kind().resource() == ResourceType.FLUID) {
      FluidUtil.interactWithFluidHandler(player, payload.hand(), menu.port().fluidTank());
    } else if (menu.port().kind().resource() == ResourceType.CHEMICAL) {
      ChemicalPlatform.interact(player, payload.hand(), menu.port());
    } else {
      return;
    }
    PacketDistributor.sendToPlayer(player, MachinePayloads.resourceStatus(menu.port()));
  }

  static void handleFactoryAction(FactoryThreadActionPayload payload, IPayloadContext context) {
    if (!(context.player() instanceof ServerPlayer player)
        || !(player.containerMenu instanceof FactoryControllerMenu menu)
        || !menu.stillValid(player)
        || !menu.factory().getBlockPos().equals(payload.position())) {
      return;
    }
    var change = switch (payload.action()) {
      case SET_LOCKED_RECIPE -> Change.lockedRecipe(payload.lockedRecipe());
      case SET_ENABLED -> Change.enabled(payload.enabled());
      case SET_PARALLEL_LIMIT -> Change.parallelLimit(payload.parallelLimit(), payload.upgradeRevision());
    };
    boolean changed = menu.factory().threadConfiguration()
        .update(payload.threadId(), payload.expectedRevision(), change);
    if (changed) {
      PacketDistributor.sendToPlayer(player, MachinePayloads.factoryStatus(menu.factory()));
    }
  }

}
