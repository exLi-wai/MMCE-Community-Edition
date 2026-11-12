package com.lw.modularmachine.integration.mekanism;

import com.lw.modularmachine.api.component.ChemicalStackData;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import mekanism.api.Action;
import mekanism.api.chemical.ChemicalStack;
import mekanism.common.capabilities.Capabilities;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.ApiStatus;

/**
 * Container callbacks operate on a detached item before the port and held item
 * are committed together.
 */
@ApiStatus.Internal
final class MekanismChemicalContainers {
  private static final int MAX_CONTAINER_TANKS = 64;

  private MekanismChemicalContainers() {
  }

  static boolean current(MachinePortBlockEntity port) {
    if (!(port.getLevel() instanceof ServerLevel level)) {
      return false;
    }
    ServerThreadGuard.require(level, "Chemical capability access");

    return !port.isRemoved()
        && level.hasChunkAt(port.getBlockPos())
        && level.getBlockEntity(port.getBlockPos()) == port;
  }

  static boolean interact(Player player, InteractionHand hand, MachinePortBlockEntity port) {
    if (!(port.getLevel() instanceof ServerLevel level)) {
      return false;
    }
    ServerThreadGuard.require(level, "Chemical container interaction");
    if (player.level() != level
        || !current(port)
        || !port.schemaSupported()
        || port.kind().resource() != PortKind.Resource.CHEMICAL
        || port.chemicalStartInProgress()
        || player.distanceToSqr(Vec3.atCenterOf(port.getBlockPos())) > 64) {
      return false;
    }
    var original = player.getItemInHand(hand);
    if (original.isEmpty() || original.getCount() != 1) {
      return false;
    }
    long revision = port.resourceRevision();
    var before = original.copy();
    var prepared = before.copy();
    var handler = Capabilities.CHEMICAL.getCapability(prepared);
    if (handler == null || handler.getChemicalTanks() < 1 || handler.getChemicalTanks() > MAX_CONTAINER_TANKS) {
      return false;
    }
    var transfer = ChemicalStackData.EMPTY;
    boolean intoPort = false;
    for (int tank = 0; tank < handler.getChemicalTanks(); tank++) {
      var contained = handler.getChemicalInTank(tank).copy();
      if (contained.isEmpty()) {
        continue;
      }
      long accepted = port.chemicalTank().fill(MekanismIntegration.data(contained), true);
      if (accepted == 0) {
        continue;
      }
      var removed = handler.extractChemical(tank, accepted, Action.EXECUTE);
      if (removed.getAmount() <= 0
          || removed.getAmount() > accepted
          || !ChemicalStack.isSameChemical(removed, contained)) {
        return false;
      }
      transfer = MekanismIntegration.data(removed);
      intoPort = true;
      break;
    }
    if (transfer.isEmpty()) {
      var stored = port.chemicalTank().getStack();
      if (stored.isEmpty()) {
        return false;
      }
      var offered = MekanismIntegration.stack(stored);
      var remainder = handler.insertChemical(offered.copy(), Action.EXECUTE);
      if (remainder.getAmount() < 0
          || remainder.getAmount() > offered.getAmount()
          || !remainder.isEmpty() && !ChemicalStack.isSameChemical(offered, remainder)) {
        return false;
      }
      transfer = stored.withAmount(stored.amount() - remainder.getAmount());
      if (transfer.isEmpty()) {
        return false;
      }
    }
    if (!current(port)
        || !port.schemaSupported()
        || port.resourceRevision() != revision
        || player.getItemInHand(hand) != original
        || !ItemStack.matches(original, before)
        || prepared.getCount() != 1) {
      return false;
    }
    var lease = port.kind().isInput() ? port.acquireChemicalStart(revision) : port.acquireChemicalOutputStart(revision);
    boolean committed = false;
    try {
      if (!(intoPort ? lease.fillExact(transfer) : lease.drainExact(transfer))) {
        return false;
      }
      if (hand == InteractionHand.MAIN_HAND) {
        player.getInventory().items.set(player.getInventory().selected, prepared);
      } else {
        player.getInventory().offhand.set(0, prepared);
      }
      committed = true;
    } finally {
      lease.resolve(committed);
    }
    lease.publish();
    player.getInventory().setChanged();
    player.containerMenu.broadcastChanges();

    return true;
  }
}
