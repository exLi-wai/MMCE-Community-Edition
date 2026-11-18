package com.lw.modularmachine.machine.recipe.resource.pneumatic;

import com.lw.modularmachine.api.recipe.CompressedAirRecipeData;
import com.lw.modularmachine.api.recipe.CompressedAirRecipeData.Amount;
import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.recipe.resource.PreparedRecipeResource;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecution;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecutionRegistry;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceTransaction;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.util.Mods;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class CompressedAirRecipeExecution {
  private CompressedAirRecipeExecution() {
  }

  public static void register() {
    RecipeResourceExecutionRegistry.register(
        RecipeResourceDefinitions.COMPRESSED_AIR,
        input -> outputPlan(input.ticket(), Math.multiplyExact(output(input.data()), input.instances())),
        CompressedAirRecipeExecution::prepareStart,
        new RecipeResourceExecution.Tick<>() {
          @Override
          public boolean required(CompressedAirRecipeData data) {
            return false;
          }

          @Override
          public RecipeResourceExecution.TickResult prepare(StructureService.StructureSubject owner, List<RecipeResourceExecution.Run<CompressedAirRecipeData>> runs) {
            throw new IllegalStateException("Compressed air is not a per-tick resource");
          }
        },
        CompressedAirRecipeExecution::prepareFinish,
        CompressedAirRecipeData::scaled,
        (data, registries) -> {
          if (!Mods.PNEUMATIC_CRAFT.isLoaded()) {
            throw new IllegalArgumentException("Compressed air requires PneumaticCraft");
          }
        });
  }

  public static boolean available(RecipeExecutionTicket ticket, CompressedAirRecipeData data) {
    return data.input().isEmpty() || inputs(ticket, List.of(data.input().orElseThrow())) != null;
  }

  public static RecipeResourcePlanning searchOutputs(RecipeExecutionTicket ticket, CompressedAirRecipeData data) {
    return outputPlan(ticket, output(data));
  }

  private static PreparedRecipeResource prepareStart(List<RecipeResourceExecution.Input<CompressedAirRecipeData>> inputs) {
    var claims = new ArrayList<Amount>();
    try {
      for (var input : inputs) {
        var amount = input.data().input();
        if (amount.isPresent()) {
          var value = amount.orElseThrow();
          claims.add(new Amount(value.pressure(), Math.multiplyExact(value.air(), input.instances())));
        }
      }
      return inputs(inputs.getFirst().ticket(), claims);
    } catch (ArithmeticException overflow) {
      return null;
    }
  }

  private static Map<Float, Long> combine(List<Amount> claims) {
    var totals = new TreeMap<Float, Long>(Comparator.reverseOrder());
    for (var claim : claims) {
      float pressure = claim.pressure() == 0 ? 0 : claim.pressure();
      totals.merge(pressure, claim.air(), Math::addExact);
    }
    return totals;
  }

  private static PreparedRecipeResource inputs(RecipeExecutionTicket ticket, List<Amount> claims) {
    if (claims.isEmpty()) {
      return new Batch(List.of());
    }
    var input = MachineRegistries.COMPRESSED_AIR_INPUT;
    return inputs(ports(ticket, input == null ? null : input.get()), claims);
  }

  static PreparedRecipeResource inputs(List<CompressedAirPort> ports, List<Amount> claims) {
    var totals = combine(claims);
    var initial = new int[ports.size()];
    var remaining = new int[ports.size()];
    for (int index = 0; index < ports.size(); index++) {
      initial[index] = ports.get(index).air();
      remaining[index] = initial[index];
    }
    for (var claim : totals.entrySet()) {
      long needed = claim.getValue();
      for (int index = 0; index < ports.size() && needed > 0; index++) {
        int stock = remaining[index];
        if (stock <= 0 || stock / (float) ports.get(index).volume() < claim.getKey()) {
          continue;
        }
        int used = (int) Math.min(stock, needed);
        remaining[index] -= used;
        needed -= used;
      }
      if (needed != 0) {
        return null;
      }
    }
    var changes = new ArrayList<AirChange>();
    for (int index = 0; index < ports.size(); index++) {
      var port = ports.get(index);
      int before = initial[index];
      if (before != remaining[index]) {
        changes.add(new AirChange(port, before, remaining[index] - before));
      }
    }
    return new Batch(changes);
  }

  private static RecipeResourceExecution.Output prepareFinish(RecipeExecutionTicket ticket, List<RecipeResourceExecution.Run<CompressedAirRecipeData>> runs) {
    long total = 0;
    try {
      for (var run : runs) {
        total = Math.addExact(total, output(run.data()));
      }
    } catch (ArithmeticException overflow) {
      return new OutputPlan(RecipeResourcePlanning.Status.REJECTED, 0);
    }
    return outputPlan(ticket, total);
  }

  private static long output(CompressedAirRecipeData data) {
    return data.output().map(Amount::air).orElse(0L);
  }

  private static OutputPlan outputPlan(RecipeExecutionTicket ticket, long quantity) {
    var status = outputs(
        ticket,
        quantity) == null ? RecipeResourcePlanning.Status.REJECTED : RecipeResourcePlanning.Status.READY;
    return new OutputPlan(status, quantity);
  }

  private static Batch outputs(RecipeExecutionTicket ticket, long quantity) {
    if (quantity == 0) {
      return new Batch(List.of());
    }
    var output = MachineRegistries.COMPRESSED_AIR_OUTPUT;
    var changes = new ArrayList<AirChange>();
    long remaining = quantity;
    for (var port : ports(ticket, output == null ? null : output.get())) {
      int accepted = (int) Math.min(remaining, port.outputSpace());
      if (accepted > 0) {
        changes.add(new AirChange(port, port.air(), accepted));
        remaining -= accepted;
      }
      if (remaining == 0) {
        return new Batch(changes);
      }
    }
    return null;
  }

  private static List<CompressedAirPort> ports(RecipeExecutionTicket ticket, Block block) {
    if (block == null) {
      return List.of();
    }
    var ports = new ArrayList<CompressedAirPort>();
    for (var position : ticket.componentPositions(block)) {
      if (ticket.level().getBlockEntity(position) instanceof CompressedAirPort port) {
        ports.add(port);
      }
    }
    return ports;
  }

  private record OutputPlan(Status status, long quantity) implements RecipeResourceExecution.Output {
    @Override
    public Progress advance(int budget) {
      return new Progress(status, 0);
    }

    @Override
    public PreparedRecipeResource prepared(RecipeExecutionTicket ticket) {
      return status == Status.READY ? outputs(ticket, quantity) : null;
    }
  }

  private record AirChange(CompressedAirPort port, int before, int delta) {
    private boolean current() {
      return port.air() == before && (delta < 0 || port.outputSpace() >= delta);
    }
  }

  private static final class Batch implements PreparedRecipeResource {
    private final RecipeResourceTransaction transaction = new RecipeResourceTransaction();
    private final List<AirChange> changes;

    private Batch(List<AirChange> changes) {
      this.changes = List.copyOf(changes);
    }

    @Override
    public boolean current() {
      return changes.stream().allMatch(AirChange::current);
    }

    @Override
    public void acquire() {
      var phase = transaction.phase();
      for (var change : changes) {
        phase.enlist(change.port().acquireAirChange(change.before(), change.delta()));
      }
    }

    @Override
    public void resolve(boolean commit) {
      transaction.resolve(commit);
    }

    @Override
    public void publish() {
      changes.forEach(change -> change.port().publishAirChange());
    }
  }
}
