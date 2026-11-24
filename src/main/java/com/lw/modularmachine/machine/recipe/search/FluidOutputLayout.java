package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.api.recipe.FluidRequirement;
import com.lw.modularmachine.machine.PortKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.ApiStatus;

/** Reuses the exact, resumable slot allocator for single-fluid output tanks. */
@ApiStatus.Internal
public final class FluidOutputLayout {
  private final List<String> parts;
  private final ResourceSnapshot<FluidResourceKey> snapshot;
  private final HolderLookup.Provider registries;

  public FluidOutputLayout(RecipeExecutionTicket ticket, HolderLookup.Provider registries) {
    this(new Capture(ticket, registries).finish());
  }

  private FluidOutputLayout(Capture capture) {
    registries = capture.registries;
    parts = List.copyOf(capture.ids);
    snapshot = new ResourceSnapshot<>(capture.ticket.structureRevision(), Map.of(), capture.spaces);
  }

  public static final class Capture {
    private final RecipeExecutionTicket ticket;
    private final HolderLookup.Provider registries;
    private final List<String> ids = new ArrayList<>();
    private final List<ResourceSnapshot.OutputSlot<FluidResourceKey>> spaces = new ArrayList<>();
    private int index;

    public Capture(RecipeExecutionTicket ticket, HolderLookup.Provider registries) {
      this.ticket = ticket;
      this.registries = registries;
    }

    public int advance(int budget) {
      int used = 0;
      while (index < ticket.ports().size() && used < budget) {
        used++;
        var port = ticket.ports().get(index++);
        if (port.kind() == PortKind.FLUID_OUTPUT) {
          var stored = port.fluid();
          String selector = port.selector();
          ids.add(port.partId());
          spaces.add(
              new ResourceSnapshot.OutputSlot<>(
                  stored.isEmpty() ? null : new FluidResourceKey(stored, registries).withSelector(port.selector()),
                  stored.getAmount(),
                  port.capacity(),
                  (key, capacity) -> selector.equals(key.selector()) ? capacity : 0));
        }
      }
      return used;
    }

    private Capture finish() {
      advance(Integer.MAX_VALUE);
      return this;
    }

    public boolean complete() {
      return index == ticket.ports().size();
    }

    public FluidOutputLayout layout() {
      if (!complete()) {
        throw new IllegalStateException("Incomplete fluid output capture");
      }
      return new FluidOutputLayout(this);
    }
  }

  public Planning plan(List<FluidStack> requirements) {
    return plan(requirements, "");
  }

  public Planning plan(List<FluidStack> requirements, String selector) {
    var claims = new ArrayList<RecipeCandidate.Output<FluidResourceKey>>();
    for (var fluid : requirements) {
      claims.add(
          new RecipeCandidate.Output<>(
              new FluidResourceKey(fluid, registries).withSelector(selector),
              fluid.getAmount()));
    }

    return planClaims(claims);
  }

  public Planning planClaims(List<RecipeCandidate.Output<FluidResourceKey>> claims) {
    return new Planning(
        claims.isEmpty() ? null : RecipePlanner
            .prepareOutputs(snapshot, RecipeCandidate.outputOnly("fluid_outputs", claims)));
  }

  public Planning planPaidOutputs(RecipePlanner.PaidOutputs<FluidResourceKey> outputs) {
    return new Planning(outputs.prepare(snapshot));
  }

  public Planning planRequirements(List<FluidRequirement> requirements, String selector) {
    return planClaims(
        requirements.stream()
            .map(
                value -> new RecipeCandidate.Output<>(
                    new FluidResourceKey(value.stack(), registries).withSelector(value.selector(selector)),
                    value.stack().getAmount(),
                    false,
                    value.ignoreOutputCheck()))
            .toList());
  }

  public final class Planning {
    private final RecipePlanner.Session<FluidResourceKey> session;
    private RecipePlan<FluidResourceKey> selected;
    private RecipePlanner.Status status;

    private Planning(RecipePlanner.Session<FluidResourceKey> session) {
      this.session = session;
      status = session == null ? RecipePlanner.Status.SELECTED : RecipePlanner.Status.BUDGET_EXHAUSTED;
    }

    public RecipePlanner.Status status() {
      return status;
    }

    public RecipePlanner.SearchResult<FluidResourceKey> advance(int budget) {
      if (session == null) {
        throw new IllegalStateException("Empty fluid output needs no planning");
      }
      var result = session.advance(budget);
      selected = result.plan().orElse(null);
      status = result.status();

      return result;
    }

    public Map<String, FluidStack> additions() {
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

    public Set<FluidResourceKey> discardedOverflow() {
      return session == null ? Set.of() : session.discardedOverflow();
    }

    public Set<String> targetParts() {
      var targets = new HashSet<String>();
      for (var insertion : selected.insertions()) {
        targets.add(parts.get(insertion.slot()));
      }
      return targets;
    }
  }
}
