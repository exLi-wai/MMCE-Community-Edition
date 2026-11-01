package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.PortKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.neoforged.neoforge.fluids.FluidStack;

/** Reuses the exact, resumable slot allocator for single-fluid output tanks. */
final class FluidOutputLayout {
  private final List<String> parts;
  private final ItemResourceSnapshot<FluidResourceKey> snapshot;
  private final HolderLookup.Provider registries;
  private final List<String> selectors;

  FluidOutputLayout(RecipeExecutionTicket ticket, HolderLookup.Provider registries) {
    this.registries = registries;
    var ids = new ArrayList<String>();
    var labels = new ArrayList<String>();
    var spaces = new ArrayList<ItemResourceSnapshot.OutputSlot<FluidResourceKey>>();
    for (var port : ticket.ports()) {
      if (port.kind() == PortKind.FLUID_OUTPUT) {
        var stored = port.fluid();
        ids.add(port.partId());
        labels.add(port.selector());
        spaces.add(
            new ItemResourceSnapshot.OutputSlot<>(
                stored.isEmpty() ? null : new FluidResourceKey(stored, registries).withSelector(port.selector()),
                stored.getAmount(),
                port.capacity()));
      }
    }
    parts = List.copyOf(ids);
    selectors = List.copyOf(labels);
    snapshot = new ItemResourceSnapshot<>(ticket.structureRevision(), Map.of(), spaces);
  }

  Planning plan(List<FluidStack> requirements) {
    return plan(requirements, "");
  }

  Planning plan(List<FluidStack> requirements, String selector) {
    var claims = new ArrayList<RecipeCandidate.Output<FluidResourceKey>>();
    for (var fluid : requirements) {
      claims.add(
          new RecipeCandidate.Output<>(
              new FluidResourceKey(fluid, registries).withSelector(selector),
              fluid.getAmount()));
    }

    return planClaims(claims);
  }

  Planning planClaims(List<RecipeCandidate.Output<FluidResourceKey>> claims) {
    return new Planning(claims);
  }

  final class Planning {
    private final RecipePlanner.Session<FluidResourceKey> session;
    private RecipePlan<FluidResourceKey> selected;
    private RecipePlanner.Status status;

    private Planning(List<RecipeCandidate.Output<FluidResourceKey>> claims) {
      var spaces = new ArrayList<ItemResourceSnapshot.OutputSlot<FluidResourceKey>>();
      for (int index = 0; index < snapshot.outputs().size(); index++) {
        var slot = snapshot.outputs().get(index);
        var limits = new HashMap<FluidResourceKey, Long>();
        for (var claim : claims) {
          limits.put(claim.key(), selectors.get(index).equals(claim.key().selector()) ? slot.capacity() : 0L);
        }
        spaces.add(new ItemResourceSnapshot.OutputSlot<>(slot.key(), slot.count(), slot.capacity(), limits));
      }
      var selectedDomain = new ItemResourceSnapshot<FluidResourceKey>(snapshot.revision(), Map.of(), spaces);
      session = claims.isEmpty() ? null : RecipePlanner
          .prepareOutputs(selectedDomain, RecipeCandidate.outputOnly("fluid_outputs", claims));
      status = session == null ? RecipePlanner.Status.SELECTED : RecipePlanner.Status.BUDGET_EXHAUSTED;
    }

    RecipePlanner.Status status() {
      return status;
    }

    RecipePlanner.SearchResult<FluidResourceKey> advance(int budget) {
      if (session == null) {
        throw new IllegalStateException("Empty fluid output needs no planning");
      }
      var result = session.advance(budget);
      selected = result.plan().orElse(null);
      status = result.status();

      return result;
    }

    Map<String, FluidStack> additions() {
      if (session == null) {
        return Map.of();
      }
      if (selected == null) {
        throw new IllegalStateException("Fluid outputs have not been planned");
      }
      var result = new LinkedHashMap<String, FluidStack>();
      for (var insertion : selected.insertions()) {
        var part = parts.get(insertion.slot());
        if (result.put(part, insertion.key().amount(Math.toIntExact(insertion.count()))) != null) {
          throw new IllegalArgumentException("Fluid output tank claimed twice");
        }
      }

      return Map.copyOf(result);
    }
  }
}
