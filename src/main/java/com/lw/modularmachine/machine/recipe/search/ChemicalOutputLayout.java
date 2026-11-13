package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.PortKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.lw.modularmachine.api.component.ChemicalStackData;
import com.lw.modularmachine.api.recipe.ChemicalRequirement;
import org.jetbrains.annotations.ApiStatus;

/**
 * Reuses the exact, resumable slot allocator for single-chemical output tanks.
 */
@ApiStatus.Internal
public final class ChemicalOutputLayout {
  private final List<String> parts;
  private final ResourceSnapshot<ChemicalResourceKey> snapshot;

  private final List<String> selectors;

  public ChemicalOutputLayout(RecipeExecutionTicket ticket) {

    var ids = new ArrayList<String>();
    var labels = new ArrayList<String>();
    var spaces = new ArrayList<ResourceSnapshot.OutputSlot<ChemicalResourceKey>>();
    for (var port : ticket.ports()) {
      if (port.kind() == PortKind.CHEMICAL_OUTPUT) {
        var stored = port.chemical();
        ids.add(port.partId());
        labels.add(port.selector());
        spaces.add(
            new ResourceSnapshot.OutputSlot<>(
                stored.isEmpty() ? null : new ChemicalResourceKey(stored.chemical(), port.selector()),
                stored.amount(),
                Math.max(stored.amount(), port.capacity())));
      }
    }
    parts = List.copyOf(ids);
    selectors = List.copyOf(labels);
    snapshot = new ResourceSnapshot<>(ticket.structureRevision(), Map.of(), spaces);
  }

  public Planning plan(List<ChemicalRequirement> requirements) {
    return planClaims(
        requirements.stream()
            .map(
                value -> new RecipeCandidate.Output<>(
                    new ChemicalResourceKey(value.chemical(), value.selector()),
                    value.amount()))
            .toList());
  }
  public Planning planClaims(List<RecipeCandidate.Output<ChemicalResourceKey>> claims) {
    return new Planning(claims);
  }

  public final class Planning {
    private final RecipePlanner.Session<ChemicalResourceKey> session;
    private RecipePlan<ChemicalResourceKey> selected;
    private RecipePlanner.Status status;

    private Planning(List<RecipeCandidate.Output<ChemicalResourceKey>> claims) {
      var spaces = new ArrayList<ResourceSnapshot.OutputSlot<ChemicalResourceKey>>();
      for (int index = 0; index < snapshot.outputs().size(); index++) {
        var slot = snapshot.outputs().get(index);
        var limits = new HashMap<ChemicalResourceKey, Long>();
        for (var claim : claims) {
          limits.put(claim.key(), selectors.get(index).equals(claim.key().selector()) ? slot.capacity() : 0L);
        }
        spaces.add(new ResourceSnapshot.OutputSlot<>(slot.key(), slot.count(), slot.capacity(), limits));
      }
      var selectedDomain = new ResourceSnapshot<ChemicalResourceKey>(snapshot.revision(), Map.of(), spaces);
      session = claims.isEmpty() ? null : RecipePlanner
          .prepareOutputs(selectedDomain, RecipeCandidate.outputOnly("chemical_outputs", claims));
      status = session == null ? RecipePlanner.Status.SELECTED : RecipePlanner.Status.BUDGET_EXHAUSTED;
    }

    public RecipePlanner.Status status() {
      return status;
    }

    public RecipePlanner.SearchResult<ChemicalResourceKey> advance(int budget) {
      if (session == null) {
        throw new IllegalStateException("Empty chemical output needs no planning");
      }
      var result = session.advance(budget);
      selected = result.plan().orElse(null);
      status = result.status();

      return result;
    }

    public Map<String, ChemicalStackData> additions() {
      if (session == null) {
        return Map.of();
      }
      if (selected == null) {
        throw new IllegalStateException("Chemical outputs have not been planned");
      }
      var result = new LinkedHashMap<String, ChemicalStackData>();
      for (var insertion : selected.insertions()) {
        var part = parts.get(insertion.slot());
        if (result.put(part, insertion.key().amount(insertion.count())) != null) {
          throw new IllegalArgumentException("Chemical output tank claimed twice");
        }
      }

      return Map.copyOf(result);
    }
  }
}
