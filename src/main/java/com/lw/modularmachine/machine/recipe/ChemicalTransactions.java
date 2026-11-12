package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.component.ChemicalStackData;
import com.lw.modularmachine.api.recipe.ChemicalRequirement;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.machine.PortKind;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal physical chemical accounting shared by ordinary and factory
 * transactions.
 */
@ApiStatus.Internal
final class ChemicalTransactions {
  private ChemicalTransactions() {
  }

  record InputClaim(RecipeExecutionTicket ticket, List<ChemicalRequirement> requirements) {
  }

  static List<ChemicalRequirement> scaled(List<ChemicalRequirement> requirements, int instances) {
    return requirements.stream()
        .map(
            value -> new ChemicalRequirement(
                value.chemical(),
                Math.multiplyExact(value.amount(), instances),
                value.selector()))
        .toList();
  }

  static Batch inputs(RecipeExecutionTicket ticket, List<ChemicalRequirement> requirements) {
    return inputs(List.of(new InputClaim(ticket, requirements)));
  }

  static Batch inputs(List<InputClaim> claims) {
    var changes = new LinkedHashMap<MachinePortBlockEntity, Change>();
    for (var claim : claims) {
      for (var demand : claim.requirements()) {
        long remaining = demand.amount();
        for (var port : claim.ticket().ports()) {
          if (port.kind() != PortKind.CHEMICAL_INPUT
              || !port.selector().equals(demand.selector())
              || !port.chemical().chemical().equals(demand.chemical())) {
            continue;
          }
          var previous = changes.get(port.identity());
          if (previous != null && previous.port().revision() != port.revision()) {
            return null;
          }
          long reserved = previous == null ? 0 : previous.amount().amount();
          long take = Math.min(remaining, port.chemical().amount() - reserved);
          if (take > 0) {
            changes.put(port.identity(), new Change(port, new ChemicalStackData(demand.chemical(), reserved + take)));
            remaining -= take;
          }
          if (remaining == 0) {
            break;
          }
        }
        if (remaining != 0) {
          return null;
        }
      }
    }
    return new Batch(List.copyOf(changes.values()), false);
  }

  static Batch outputs(RecipeExecutionTicket ticket, Map<String, ChemicalStackData> additions) {
    var changes = new ArrayList<Change>();
    var found = new IdentityHashMap<MachinePortBlockEntity, Boolean>();
    for (var port : ticket.ports()) {
      var amount = additions.get(port.partId());
      if (amount != null) {
        if (port.kind() != PortKind.CHEMICAL_OUTPUT || found.put(port.identity(), true) != null) {
          throw new IllegalStateException("Invalid chemical output identity");
        }
        changes.add(new Change(port, amount));
      }
    }
    if (changes.size() != additions.size()) {
      throw new IllegalStateException("Missing chemical output port");
    }
    return new Batch(List.copyOf(changes), true);
  }

  static int arithmeticLimit(MachineRecipe definition, int limit) {
    for (var category : List
        .of(definition.chemicals().inputs(), definition.chemicals().outputs(), definition.chemicals().perTick())) {
      var totals = new LinkedHashMap<ChemicalResourceKey, Long>();
      for (var requirement : category) {
        totals.merge(
            new ChemicalResourceKey(requirement.chemical(), requirement.selector()),
            requirement.amount(),
            Math::addExact);
      }
      for (long amount : totals.values()) {
        limit = (int) Math.min(limit, Long.MAX_VALUE / amount);
      }
    }
    return limit;
  }

  static boolean missingInputs(RecipeExecutionTicket ticket, List<ChemicalRequirement> requirements) {
    return requirements.stream()
        .anyMatch(value -> !RecipeResourceRequirements.has(ticket, PortKind.CHEMICAL_INPUT, value.selector()));
  }

  static Batch fees(RecipeExecutionTicket ticket, List<FrozenItemRun> runs) {
    var claims = new ArrayList<InputClaim>();
    for (var run : runs) {
      if (!run.chemicalPerTick().isEmpty()) {
        claims.add(new InputClaim(ticket.withInputGroup(run.inputGroup()), run.chemicalPerTick()));
      }
    }
    return inputs(claims);
  }

  static boolean stillUnavailable(StructureService.StructureSubject owner, List<FrozenItemRun> runs) {
    var ticket = RecipeExecutionTicket.captureChemicalInputs(owner).orElse(null);
    if (ticket == null || runs.stream().anyMatch(run -> !run.matchesInputGroups(ticket))) {
      return false;
    }
    return fees(ticket, runs) == null;
  }

  private record Change(RecipeExecutionTicket.PortSnapshot port, ChemicalStackData amount) {
  }

  static final class Batch {
    private final List<Change> changes;
    private final boolean output;
    private final List<MachinePortBlockEntity.ChemicalStartLease> leases = new ArrayList<>();

    private Batch(List<Change> changes, boolean output) {
      this.changes = changes;
      this.output = output;
    }

    void acquire() {
      for (var change : changes) {
        var port = change.port();
        leases.add(
            output ? port.identity().acquireChemicalOutputStart(port.revision()) : port.identity()
                .acquireChemicalStart(port.revision()));
      }
      for (int index = 0; index < changes.size(); index++) {
        var amount = changes.get(index).amount();
        boolean accepted = output ? leases.get(index).fillExact(amount) : leases.get(index).drainExact(amount);
        if (!accepted) {
          throw new IllegalStateException("Chemical contents changed during transaction");
        }
      }
    }

    void resolve(boolean commit) {
      for (var lease : leases) {
        lease.resolve(commit);
      }
    }

    void publish() {
      for (var lease : leases) {
        try {
          lease.publish();
        } catch (RuntimeException notification) {
          ModularMachine.LOGGER.warn("Committed chemical resource notification failed", notification);
        }
      }
    }
  }
}
