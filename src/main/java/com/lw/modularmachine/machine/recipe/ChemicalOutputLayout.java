package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.PortKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.lw.modularmachine.api.component.ChemicalStackData;
import com.lw.modularmachine.api.recipe.ChemicalRequirement;

/**
 * Reuses the exact, resumable slot allocator for single-chemical output tanks.
 */
final class ChemicalOutputLayout {
  private final List<String> parts;
  private final ItemResourceSnapshot<ChemicalResourceKey> snapshot;

  private final List<String> selectors;

  ChemicalOutputLayout(RecipeExecutionTicket ticket) {

    var ids = new ArrayList<String>();
    var labels = new ArrayList<String>();
    var spaces = new ArrayList<ItemResourceSnapshot.OutputSlot<ChemicalResourceKey>>();
    for (var port : ticket.ports()) {
      if (port.kind() == PortKind.CHEMICAL_OUTPUT) {
        var stored = port.chemical();
        ids.add(port.partId());
        labels.add(port.selector());
        spaces.add(
            new ItemResourceSnapshot.OutputSlot<>(
                stored.isEmpty() ? null : new ChemicalResourceKey(stored.chemical(), port.selector()),
                stored.amount(),
                Math.max(stored.amount(), port.capacity())));
      }
    }
    parts = List.copyOf(ids);
    selectors = List.copyOf(labels);
    snapshot = new ItemResourceSnapshot<>(ticket.structureRevision(), Map.of(), spaces);
  }

  Planning plan(List<ChemicalRequirement> requirements) {
    return planClaims(
        requirements.stream()
            .map(
                value -> new RecipeCandidate.Output<>(
                    new ChemicalResourceKey(value.chemical(), value.selector()),
                    value.amount()))
            .toList());
  }
  Planning planClaims(List<RecipeCandidate.Output<ChemicalResourceKey>> claims) {
    return new Planning(claims);
  }

  final class Planning {
    private final RecipePlanner.Session<ChemicalResourceKey> session;
    private RecipePlan<ChemicalResourceKey> selected;
    private RecipePlanner.Status status;

    private Planning(List<RecipeCandidate.Output<ChemicalResourceKey>> claims) {
      var spaces = new ArrayList<ItemResourceSnapshot.OutputSlot<ChemicalResourceKey>>();
      for (int index = 0; index < snapshot.outputs().size(); index++) {
        var slot = snapshot.outputs().get(index);
        var limits = new HashMap<ChemicalResourceKey, Long>();
        for (var claim : claims) {
          limits.put(claim.key(), selectors.get(index).equals(claim.key().selector()) ? slot.capacity() : 0L);
        }
        spaces.add(new ItemResourceSnapshot.OutputSlot<>(slot.key(), slot.count(), slot.capacity(), limits));
      }
      var selectedDomain = new ItemResourceSnapshot<ChemicalResourceKey>(snapshot.revision(), Map.of(), spaces);
      session = claims.isEmpty() ? null : RecipePlanner
          .prepareOutputs(selectedDomain, RecipeCandidate.outputOnly("chemical_outputs", claims));
      status = session == null ? RecipePlanner.Status.SELECTED : RecipePlanner.Status.BUDGET_EXHAUSTED;
    }

    RecipePlanner.Status status() {
      return status;
    }

    RecipePlanner.SearchResult<ChemicalResourceKey> advance(int budget) {
      if (session == null) {
        throw new IllegalStateException("Empty chemical output needs no planning");
      }
      var result = session.advance(budget);
      selected = result.plan().orElse(null);
      status = result.status();

      return result;
    }

    Map<String, ChemicalStackData> additions() {
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
