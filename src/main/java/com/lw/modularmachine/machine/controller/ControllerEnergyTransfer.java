package com.lw.modularmachine.machine.controller;

import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.machine.structure.StructureService;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;
/**
 * Legacy exact energy-throughput operation, bound port cache and transfer
 * telemetry.
 */
@ApiStatus.Internal
public final class ControllerEnergyTransfer {
  public interface Host {
    StructureService.Registration registration();
    boolean canCommitProduction();
    boolean revalidateProduction(StructureService.Registration registration);
    void markChanged();
  }
  private final Host host;
  private MachinePortBlockEntity input;
  private MachinePortBlockEntity output;
  private long successfulTransfers;
  private boolean transferredThisTick;
  private long bindingRebuilds;

  public ControllerEnergyTransfer(Host host) {
    this.host = host;
  }
  public void beginTick() {
    transferredThisTick = false;
  }
  public void detach() {
    input = null;
    output = null;
  }
  public boolean transferred() {
    return transferredThisTick;
  }
  public long successfulTransfers() {
    return successfulTransfers;
  }
  public long bindingRebuilds() {
    return bindingRebuilds;
  }
  public void restoreTransfers(long value) {
    successfulTransfers = Math.max(0, value);
  }

  public void advance(ServerLevel server, StructureService.Registration ticket, long amount) {
    if (!(server.getBlockEntity(ticket.pattern.energyInputPosition()) instanceof MachinePortBlockEntity foundInput)
        || !(server.getBlockEntity(ticket.pattern.energyOutputPosition()) instanceof MachinePortBlockEntity foundOutput)
        || !foundInput.schemaSupported()
        || !foundOutput.schemaSupported()) {
      return;
    }
    if (input != foundInput || output != foundOutput) {
      input = foundInput;
      output = foundOutput;
      bindingRebuilds = addTelemetry(bindingRebuilds, 1);
    }
    var source = input;
    var target = output;
    if (source == null
        || target == null
        || source == target
        || host.registration() != ticket
        || !host.canCommitProduction()) {
      return;
    }
    if (!source.energy().canTransferExactTo(target.energy(), amount)) {
      return;
    }
    if (!host.revalidateProduction(ticket)) {
      return;
    }
    if (host.registration() != ticket
        || !host.canCommitProduction()
        || server.getBlockEntity(ticket.pattern.energyInputPosition()) != source
        || server.getBlockEntity(ticket.pattern.energyOutputPosition()) != target) {
      return;
    }
    if (source.energy().transferExactTo(target.energy(), amount)) {
      transferredThisTick = true;
      successfulTransfers = addTelemetry(successfulTransfers, 1);
      host.markChanged();
    }
  }

  private static long addTelemetry(long value, long delta) {
    return Long.MAX_VALUE - value < delta ? Long.MAX_VALUE : value + delta;
  }

}
