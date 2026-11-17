package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.recipe.resource.RecipeResourceTransaction;
import com.lw.modularmachine.machine.recipe.resource.PreparedRecipeResource;
import com.lw.modularmachine.machine.recipe.search.ChemicalResourceKey;
import com.lw.modularmachine.machine.recipe.search.RecipeResourceRequirements;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.component.ChemicalStackData;
import com.lw.modularmachine.api.recipe.ChemicalRequirement;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.machine.PortKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal physical chemical accounting shared by ordinary and factory
 * transactions.
 */
@ApiStatus.Internal
public final class ChemicalTransactions {
  private ChemicalTransactions() {
  }

  public record InputClaim(RecipeExecutionTicket ticket, List<ChemicalRequirement> requirements) {
  }

  public static List<ChemicalRequirement> scaled(List<ChemicalRequirement> requirements, int instances) {
    return requirements.stream().map(value -> value.scaled(instances)).toList();
  }

  public static Batch inputs(RecipeExecutionTicket ticket, List<ChemicalRequirement> requirements) {
    return inputs(List.of(new InputClaim(ticket, requirements)));
  }

  /**
   * Read-only admission check; no transaction batch escapes to search callers.
   */
  public static boolean inputsAvailable(RecipeExecutionTicket ticket, List<ChemicalRequirement> requirements) {
    ticket.requireServerThread();
    return inputs(ticket, requirements) != null;
  }

  public static Batch inputs(List<InputClaim> claims) {
    var changes = new LinkedHashMap<Object, Change>();
    for (var claim : claims) {
      for (var demand : claim.requirements()) {
        long remaining = demand.amount();
        for (var port : claim.ticket().ports()) {
          if (port.kind() != PortKind.CHEMICAL_INPUT
              || !port.selector().equals(demand.selector())
              || !port.chemical().chemical().equals(demand.chemical())) {
            continue;
          }
          var tank = port.physicalIdentity();
          var previous = changes.get(tank);
          if (previous != null && previous.port().revision() != port.revision()) {
            return null;
          }
          long reserved = previous == null ? 0 : previous.amount().amount();
          long take = Math.min(remaining, port.chemical().amount() - reserved);
          if (take > 0) {
            changes.put(tank, new Change(port, new ChemicalStackData(demand.chemical(), reserved + take)));
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

  public static Batch outputs(RecipeExecutionTicket ticket, Map<String, ChemicalStackData> additions) {
    var changes = new ArrayList<Change>();
    var found = new HashSet<Object>();
    for (var port : ticket.ports()) {
      var amount = additions.get(port.partId());
      if (amount != null) {
        if (port.kind() != PortKind.CHEMICAL_OUTPUT || !found.add(port.physicalIdentity())) {
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

  static int arithmeticLimit(MachineRecipe definition, int limit, boolean aggregate) {
    for (var category : List.of(
        definition.chemicals().inputs(),
        definition.chemicals().outputs(),
        definition.chemicals().perTick(),
        definition.chemicals().perTickOutputs())) {
      var totals = new LinkedHashMap<ChemicalResourceKey, Long>();
      for (var requirement : category) {
        if (requirement.ignoreOutputCheck()) {
          if (aggregate) {
            limit = (int) Math.min(limit, Long.MAX_VALUE / requirement.amount());
          }
          continue;
        }
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

  public static boolean missingInputs(RecipeExecutionTicket ticket, List<ChemicalRequirement> requirements) {
    return requirements.stream()
        .anyMatch(value -> !RecipeResourceRequirements.has(ticket, PortKind.CHEMICAL_INPUT, value.selector()));
  }

  static Batch fees(RecipeExecutionTicket ticket, List<FrozenRecipeRun> runs) {
    var claims = new ArrayList<InputClaim>();
    for (var run : runs) {
      if (!run.chemicalPerTick().isEmpty()) {
        var source = ticket.forRun(run).orElse(null);
        if (source == null) {
          return null;
        }
        claims.add(new InputClaim(source, run.chemicalPerTick()));
      }
    }
    return inputs(claims);
  }

  static boolean stillUnavailable(StructureService.StructureSubject owner, List<FrozenRecipeRun> runs) {
    var ticket = RecipeExecutionTicket.captureChemicalInputs(owner).orElse(null);
    if (ticket == null
        || runs.stream().anyMatch(run -> !run.matchesInputGroups(ticket) || ticket.forRun(run).isEmpty())) {
      return false;
    }
    return fees(ticket, runs) == null;
  }

  private record Change(RecipePortSnapshot port, ChemicalStackData amount) {
  }

  public static final class Batch implements PreparedRecipeResource {
    private final List<Change> changes;
    private final boolean output;
    private final List<MachinePortBlockEntity.ChemicalStartLease> leases = new ArrayList<>();
    private final RecipeResourceTransaction resources = new RecipeResourceTransaction();
    private final RecipeResourceTransaction.Phase chemicals = resources.phase();

    private Batch(List<Change> changes, boolean output) {
      this.changes = changes;
      this.output = output;
    }

    @Override
    public void addPatterns(PatternInputTransaction patterns) {
      for (var change : changes) {
        if (change.port().patternInput()) {
          if (output) {
            throw new IllegalStateException("Pattern caches are not production outputs");
          }
          patterns.consumeChemical(change.port(), change.amount());
        }
      }
    }

    @Override
    public void acquire() {
      for (var change : changes) {
        var port = change.port();
        if (port.patternInput()) {
          continue;
        }
        var lease = output ? port.identity().acquireChemicalOutputStart(port.revision(), port.tankIndex()) : port
            .identity().acquireChemicalStart(port.revision(), port.tankIndex());
        leases.add(chemicals.enlist(lease, MachinePortBlockEntity.ChemicalStartLease::resolve));
        var amount = change.amount();
        boolean accepted = output ? lease.fillExact(amount) : lease.drainExact(amount);
        if (!accepted) {
          throw new IllegalStateException("Chemical contents changed during transaction");
        }
      }
    }

    @Override
    public void resolve(boolean commit) {
      resources.resolve(commit);
    }

    @Override
    public void publish() {
      RecipeResourceTransaction.publishAll(
          leases,
          MachinePortBlockEntity.ChemicalStartLease::publish,
          notification -> ModularMachine.LOGGER.warn("Committed chemical resource notification failed", notification));
    }
  }
}
