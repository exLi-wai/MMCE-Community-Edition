package com.lw.modularmachine.machine.recipe.resource.fluid;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.recipe.FluidRequirement;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.recipe.FrozenRecipeRun;
import com.lw.modularmachine.machine.recipe.PatternInputTransaction;
import com.lw.modularmachine.machine.recipe.RecipeCommitGuard;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.recipe.RecipeFluidLeases;
import com.lw.modularmachine.machine.recipe.RecipePortSnapshot;
import com.lw.modularmachine.machine.recipe.TickOutputWork;
import com.lw.modularmachine.machine.recipe.resource.FluidRecipeData;
import com.lw.modularmachine.machine.recipe.resource.PreparedRecipeResource;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecution;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceTransaction;
import com.lw.modularmachine.machine.recipe.search.FluidOutputLayout;
import com.lw.modularmachine.machine.recipe.search.FluidResourceKey;
import com.lw.modularmachine.machine.recipe.search.RecipeCandidate;
import com.lw.modularmachine.machine.recipe.search.RecipePlanner;
import com.lw.modularmachine.machine.recipe.search.RecipeResourceRequirements;
import com.lw.modularmachine.machine.structure.StructureService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.ApiStatus;
import static com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecution.TickResult.failed;
import net.minecraft.core.BlockPos;

/**
 * Main-thread tick preparation captures full input proof and sampled writes;
 * the returned participant joins the owner's resource/progress transaction.
 * Output plans stay with their batch while its claims and tank images match.
 */
@ApiStatus.Internal
public final class FluidTickResourceExecution {

  private record Claim(RecipeExecutionTicket ticket, FluidRequirement requirement) {
  }

  private record Change(RecipePortSnapshot port, FluidStack amount, RecipeModifier.Io io) {
  }

  private sealed interface DemandCapture {
  }

  private record RejectedDemands(RecipeResourceExecution.TickStatus status) implements DemandCapture {
  }

  private record CapturedDemands(
      List<RecipeExecutionTicket> proofs,
      List<Claim> fullInputs,
      List<Claim> sampledInputs,
      List<RecipeCandidate.Output<FluidResourceKey>> outputs) implements DemandCapture {
    private CapturedDemands {
      proofs = List.copyOf(proofs);
      fullInputs = List.copyOf(fullInputs);
      sampledInputs = List.copyOf(sampledInputs);
      outputs = List.copyOf(outputs);
    }
  }

  private static final class PendingOutput implements TickOutputWork.Plan {
    private final long structureRevision;
    private final long definitionEpoch;
    private final List<Tank> tanks;
    private final List<FrozenRecipeRun> runs;
    private final List<RecipeCandidate.Output<FluidResourceKey>> claims;
    private final FluidOutputLayout.Planning plan;

    private PendingOutput(RecipeExecutionTicket ticket, List<FrozenRecipeRun> runs, List<Tank> tanks, List<RecipeCandidate.Output<FluidResourceKey>> claims) {
      structureRevision = ticket.structureRevision();
      definitionEpoch = ticket.definitionEpoch();
      this.tanks = List.copyOf(tanks);
      this.runs = List.copyOf(runs);
      this.claims = List.copyOf(claims);
      plan = new FluidOutputLayout(ticket, ticket.level().registryAccess()).planClaims(claims);
    }

    private boolean matches(RecipeExecutionTicket ticket, List<FrozenRecipeRun> runs, List<Tank> tanks, List<RecipeCandidate.Output<FluidResourceKey>> claims) {
      return structureRevision == ticket.structureRevision()
          && definitionEpoch == ticket.definitionEpoch()
          && this.tanks.equals(tanks)
          && this.runs.equals(runs)
          && this.claims.equals(claims);
    }

    @Override
    public int advance(int budget) {
      return plan.advance(budget).workUsed();
    }

    @Override
    public boolean pending() {
      return plan.status() == RecipePlanner.Status.BUDGET_EXHAUSTED;
    }
  }

  private record Tank(
      String part,
      BlockPos position,
      long revision,
      String selector,
      FluidResourceKey fluid,
      long amount,
      long capacity) {
  }

  private FluidTickResourceExecution() {
  }

  public static RecipeResourceExecution.Validation<FluidRecipeData> validation() {
    return (data, registries) -> {
      for (var rows : List.of(data.perTickInputs(), data.perTickOutputs())) {
        for (var row : rows) {
          if (row.parallelUnaffected()) {
            throw new IllegalArgumentException("Per-tick fluids cannot ignore parallel instances");
          }
        }
      }
    };
  }

  public static RecipeResourceExecution.Start<FluidRecipeData> start() {
    return inputs -> empty();
  }

  public static RecipeResourceExecution.Admission<FluidRecipeData> admission() {
    // The engine checks upgraded/frozen int boundaries before payment.
    // Independent factory runs keep separate fluid stacks; shared totals are long.
    return input -> readyPlanning();
  }

  public static RecipeResourceExecution.Freeze<FluidRecipeData> freeze() {
    // The existing engine-owned freeze applies rounded upgrades and instances.
    return (data, instances) -> data;
  }

  public static RecipeResourceExecution.Finish<FluidRecipeData> finish() {
    return (ticket, runs) -> new RecipeResourceExecution.Output() {
      @Override
      public Status status() {
        return Status.READY;
      }

      @Override
      public Progress advance(int budget) {
        return new Progress(Status.READY, 0);
      }

      @Override
      public PreparedRecipeResource prepared(RecipeExecutionTicket checked) {
        return empty();
      }
    };
  }

  public static RecipeResourceExecution.Tick<FluidRecipeData> tick() {
    return new RecipeResourceExecution.Tick<>() {
      @Override
      public boolean required(FluidRecipeData data) {
        return !data.perTickInputs().isEmpty() || !data.perTickOutputs().isEmpty();
      }

      @Override
      public RecipeResourceExecution.TickResult prepare(StructureService.StructureSubject owner, List<RecipeResourceExecution.Run<FluidRecipeData>> runs) {
        var ticket = RecipeExecutionTicket.captureTickFluids(owner).orElse(null);
        if (ticket == null) {
          return failed(RecipeResourceExecution.TickStatus.STALE);
        }

        return switch (captureDemands(ticket, runs)) {
          case RejectedDemands rejected -> failed(rejected.status());
          case CapturedDemands captured -> prepareChanges(owner, ticket, runs, captured);
        };
      }
    };
  }

  private static DemandCapture captureDemands(RecipeExecutionTicket ticket, List<RecipeResourceExecution.Run<FluidRecipeData>> runs) {
    var fullInputs = new ArrayList<Claim>();
    var sampledInputs = new ArrayList<Claim>();
    var proofs = new ArrayList<RecipeExecutionTicket>();
    var outputTotals = new LinkedHashMap<FluidResourceKey, Long>();
    for (var value : runs) {
      var run = value.run();
      if (!run.matchesInputGroups(ticket)) {
        return new RejectedDemands(RecipeResourceExecution.TickStatus.STALE);
      }
      var source = ticket.forRun(run).orElse(null);
      if (source == null) {
        return new RejectedDemands(RecipeResourceExecution.TickStatus.STALE);
      }
      proofs.add(source);
      if (!collectInputs(source, run, value.data().perTickInputs(), fullInputs, sampledInputs)) {
        return new RejectedDemands(RecipeResourceExecution.TickStatus.MISSING_INPUT);
      }
      if (!collectOutputs(ticket, run, value.data().perTickOutputs(), outputTotals)) {
        return new RejectedDemands(RecipeResourceExecution.TickStatus.MISSING_OUTPUT);
      }
    }

    var outputs = outputTotals.entrySet().stream()
        .map(entry -> new RecipeCandidate.Output<>(entry.getKey(), entry.getValue())).toList();
    return new CapturedDemands(proofs, fullInputs, sampledInputs, outputs);
  }

  private static RecipeResourceExecution.TickResult prepareChanges(StructureService.StructureSubject owner, RecipeExecutionTicket ticket, List<RecipeResourceExecution.Run<FluidRecipeData>> runs, CapturedDemands demands) {
    // Availability is proved with full quantities before sampled debits are
    // prepared. No fluid lease is acquired until the enclosing commit.
    if (planInputs(demands.fullInputs()) == null) {
      return failed(RecipeResourceExecution.TickStatus.INSUFFICIENT);
    }
    var inputChanges = planInputs(demands.sampledInputs());
    if (inputChanges == null) {
      return failed(RecipeResourceExecution.TickStatus.STALE);
    }
    var changes = new ArrayList<>(inputChanges);
    if (!demands.outputs().isEmpty()) {
      var output = planOutputs(
          owner,
          ticket,
          runs.stream().map(RecipeResourceExecution.Run::run).toList(),
          demands.outputs());
      if (output.plan.status() != RecipePlanner.Status.SELECTED) {
        return failed(
            output.plan
                .status() == RecipePlanner.Status.NO_MATCH ? RecipeResourceExecution.TickStatus.OUTPUT_BLOCKED : RecipeResourceExecution.TickStatus.STALE);
      }
      for (var entry : output.plan.additions().entrySet()) {
        var port = ticket.ports().stream().filter(value -> value.partId().equals(entry.getKey())).findFirst()
            .orElseThrow();
        changes.add(new Change(port, entry.getValue(), RecipeModifier.Io.OUTPUT));
      }
    }

    return RecipeResourceExecution.TickResult.ready(new Batch(demands.proofs(), ticket, changes));
  }

  private static boolean collectInputs(RecipeExecutionTicket source, FrozenRecipeRun run, List<FluidRequirement> rows, List<Claim> fullInputs, List<Claim> sampledInputs) {
    for (int index = 0; index < rows.size(); index++) {
      var row = rows.get(index);
      if (!RecipeResourceRequirements.has(source, PortKind.FLUID_INPUT, row.selector())) {
        return false;
      }
      fullInputs.add(new Claim(source, row));
      var sampled = sampled(run, row, index, RecipeModifier.Io.INPUT);
      if (sampled != null) {
        sampledInputs.add(new Claim(source, sampled));
      }
    }

    return true;
  }

  private static boolean collectOutputs(RecipeExecutionTicket ticket, FrozenRecipeRun run, List<FluidRequirement> rows, Map<FluidResourceKey, Long> totals) {
    for (int index = 0; index < rows.size(); index++) {
      var row = rows.get(index);
      if (!RecipeResourceRequirements.has(ticket, PortKind.FLUID_OUTPUT, row.selector())) {
        return false;
      }
      if (!run.canEmitTickOutputs() && run.triggeredResources().isEmpty()) {
        continue;
      }
      var sampled = sampled(run, row, index, RecipeModifier.Io.OUTPUT);
      if (sampled != null) {
        var stack = sampled.stack();
        totals.merge(
            new FluidResourceKey(stack, ticket.level().registryAccess()).withSelector(sampled.selector()),
            (long) stack.getAmount(),
            Math::addExact);
      }
    }

    return true;
  }

  private static PendingOutput planOutputs(StructureService.StructureSubject owner, RecipeExecutionTicket ticket, List<FrozenRecipeRun> runs, List<RecipeCandidate.Output<FluidResourceKey>> claims) {
    var tanks = ticket.ports().stream().filter(port -> port.kind() == PortKind.FLUID_OUTPUT)
        .map(
            port -> new Tank(
                port.partId(),
                port.position(),
                port.revision(),
                port.selector(),
                port.fluid().isEmpty() ? null : new FluidResourceKey(port.fluid(), ticket.level().registryAccess()),
                port.fluidAmount(),
                port.capacity()))
        .toList();
    return TickOutputWork.forOwner(owner).visit(
        ticket.level().getGameTime(),
        PendingOutput.class,
        runs.getFirst().runId(),
        plan -> plan.matches(ticket, runs, tanks, claims),
        () -> new PendingOutput(ticket, runs, tanks, claims));
  }

  private static List<Change> planInputs(List<Claim> claims) {
    var reserved = new HashMap<Object, Long>();
    var debits = new ArrayList<Change>();
    for (var claim : claims) {
      var stack = claim.requirement().stack();
      int remaining = stack.getAmount();
      for (var port : claim.ticket().ports()) {
        if (port.kind() != PortKind.FLUID_INPUT
            || !port.selector().equals(claim.requirement().selector())
            || !FluidStack.isSameFluidSameComponents(port.fluid(), stack)) {
          continue;
        }
        var physical = port.physicalIdentity();
        long already = reserved.getOrDefault(physical, 0L);
        int taken = (int) Math.min(remaining, Math.max(0, port.fluidAmount() - already));
        if (taken > 0) {
          reserved.put(physical, Math.addExact(already, taken));
          debits.add(new Change(port, stack.copyWithAmount(taken), RecipeModifier.Io.INPUT));
          remaining -= taken;
        }
        if (remaining == 0) {
          break;
        }
      }
      if (remaining != 0) {
        return null;
      }
    }

    return List.copyOf(debits);
  }

  private static FluidRequirement sampled(FrozenRecipeRun run, FluidRequirement row, int index, RecipeModifier.Io io) {
    int instances = row.parallelUnaffected() ? 1 : run.parallelInstances();
    int full = row.stack().getAmount();
    if (full % instances != 0) {
      throw new IllegalArgumentException("Frozen tick fluid quantity is not an exact per-instance demand");
    }
    int successes = row.chance() == 1 ? instances : 0;
    if (row.chance() > 0 && row.chance() < 1) {
      var random = new Random(
          run.randomSeed() ^ (io == RecipeModifier.Io.OUTPUT ? 0x1162BACE5D729FD1L : 0x3E48F1FC41A27D63L) ^ ((long) run
              .progress() << 32) ^ index);
      for (int attempt = 0; attempt < instances; attempt++) {
        if (random.nextDouble() < row.chance()) {
          successes++;
        }
      }
    }

    return successes == 0 ? null : new FluidRequirement(
        row.stack().copyWithAmount(Math.multiplyExact(full / instances, successes)),
        1,
        false,
        row.selector());
  }

  private static RecipeResourcePlanning readyPlanning() {
    return new RecipeResourcePlanning() {
      @Override
      public Status status() {
        return Status.READY;
      }

      @Override
      public Progress advance(int budget) {
        return new Progress(Status.READY, 0);
      }
    };
  }

  private static PreparedRecipeResource empty() {
    return new PreparedRecipeResource() {
      @Override
      public void acquire() {
      }

      @Override
      public void resolve(boolean commit) {
      }

      @Override
      public void publish() {
      }
    };
  }

  private static final class Batch implements PreparedRecipeResource {
    private final List<RecipeExecutionTicket> proofs;
    private final RecipeExecutionTicket outputProof;
    private final List<Change> changes;
    private final RecipeResourceTransaction transaction = new RecipeResourceTransaction();
    private final Map<Object, MachinePortBlockEntity.FluidStartLease> leases = new LinkedHashMap<>();

    private Batch(List<RecipeExecutionTicket> proofs, RecipeExecutionTicket outputProof, List<Change> changes) {
      this.proofs = List.copyOf(proofs);
      this.outputProof = outputProof;
      this.changes = List.copyOf(changes);
    }

    @Override
    public boolean current() {
      return RecipeCommitGuard.validateViews(outputProof, proofs);
    }

    @Override
    public void addPatterns(PatternInputTransaction patterns) {
      for (var change : changes) {
        if (change.io() == RecipeModifier.Io.INPUT && change.port().patternInput()) {
          patterns.consumeFluid(change.port(), change.amount());
        }
      }
    }

    @Override
    public void acquire() {
      var phase = transaction.phase();
      for (var change : changes) {
        if (change.port().patternInput()) {
          continue;
        }
        var lease = leases.computeIfAbsent(
            change.port().physicalIdentity(),
            ignored -> phase
                .enlist(RecipeFluidLeases.acquire(change.port()), MachinePortBlockEntity.FluidStartLease::resolve));
        boolean accepted = change.io() == RecipeModifier.Io.OUTPUT ? lease.fillExact(change.amount()) : lease
            .drainExact(change.amount());
        if (!accepted) {
          throw new IllegalStateException("Tick fluid allocation changed before commit");
        }
      }
    }

    @Override
    public void resolve(boolean commit) {
      transaction.resolve(commit);
    }

    @Override
    public void publish() {
      RecipeResourceTransaction.publishAll(
          leases.values(),
          MachinePortBlockEntity.FluidStartLease::publish,
          failed -> ModularMachine.LOGGER.warn("Committed tick fluid notification failed", failed));
    }
  }
}
