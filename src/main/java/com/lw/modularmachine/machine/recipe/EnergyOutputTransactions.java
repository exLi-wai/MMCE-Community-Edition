package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.structure.StructureService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Internal long-energy output participant in the existing per-tick transaction.
 */
final class EnergyOutputTransactions {
  enum Status {
    READY,
    UNAVAILABLE,
    MISSING_PORT,
    BLOCKED
  }

  private record Credit(RecipeExecutionTicket.PortSnapshot port, long amount) {
  }

  static final class Batch {
    private final Status status;
    private final RecipeExecutionTicket ticket;
    private final List<Credit> credits;
    private final List<MachinePortBlockEntity.EnergyStartLease> leases = new ArrayList<>();

    private Batch(Status status, RecipeExecutionTicket ticket, List<Credit> credits) {
      this.status = status;
      this.ticket = ticket;
      this.credits = List.copyOf(credits);
    }

    Status status() {
      return status;
    }

    boolean current() {
      return status == Status.READY && (ticket == null || RecipeCommitGuard.validate(ticket));
    }

    void acquire() {
      for (var credit : credits) {
        var lease = credit.port().identity().acquireEnergyStart(credit.port().revision());
        leases.add(lease);
        if (!lease.insertExact(credit.amount())) {
          throw new IllegalStateException("Energy output capacity or throughput changed before commit");
        }
      }
    }

    void resolve(boolean commit) {
      leases.forEach(lease -> lease.resolve(commit));
    }

    void publish() {
      for (var lease : leases) {
        try {
          lease.publish();
        } catch (RuntimeException failed) {
          ModularMachine.LOGGER.warn("Committed recipe energy output notification failed", failed);
        }
      }
    }
  }

  private EnergyOutputTransactions() {
  }

  static Batch prepare(StructureService.StructureSubject owner, List<FrozenItemRun> runs) {
    var required = new LinkedHashMap<String, Long>();
    try {
      for (var run : runs) {
        long amount = run.nextEnergyOutput();
        if (amount > 0) {
          required.merge(run.selectors().energyOutput(), amount, Math::addExact);
        }
      }
    } catch (ArithmeticException overflow) {
      return new Batch(Status.BLOCKED, null, List.of());
    }
    if (required.isEmpty()) {
      return new Batch(Status.READY, null, List.of());
    }
    var ticket = RecipeExecutionTicket.captureEnergyOutputs(owner).orElse(null);
    if (ticket == null) {
      return new Batch(Status.UNAVAILABLE, null, List.of());
    }
    var credits = new ArrayList<Credit>();
    for (var entry : required.entrySet()) {
      long remaining = entry.getValue();
      boolean found = false;
      for (var port : ticket.ports()) {
        if (port.kind() != PortKind.ENERGY_OUTPUT || !port.selector().equals(entry.getKey())) {
          continue;
        }
        found = true;
        long addition = Math.min(remaining, port.energyReceivable());
        if (addition > 0) {
          credits.add(new Credit(port, addition));
          remaining -= addition;
        }
        if (remaining == 0) {
          break;
        }
      }
      if (!found) {
        return new Batch(Status.MISSING_PORT, ticket, List.of());
      }
      if (remaining > 0) {
        return new Batch(Status.BLOCKED, ticket, List.of());
      }
    }
    return new Batch(Status.READY, ticket, credits);
  }
}
