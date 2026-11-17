package com.lw.modularmachine.machine.recipe.resource.chemical;

import com.lw.modularmachine.api.recipe.ChemicalRecipeData;
import com.lw.modularmachine.api.recipe.ChemicalRequirement;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import com.lw.modularmachine.machine.recipe.ChemicalTransactions;
import com.lw.modularmachine.machine.recipe.ChemicalRecipeSampling;
import com.lw.modularmachine.machine.chemical.ChemicalPlatform;
import com.lw.modularmachine.machine.recipe.PatternInputTransaction;
import com.lw.modularmachine.machine.recipe.RecipeCommitGuard;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.recipe.resource.PreparedRecipeResource;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecution;
import com.lw.modularmachine.machine.recipe.search.ChemicalOutputLayout;
import com.lw.modularmachine.machine.recipe.search.ChemicalResourceKey;
import com.lw.modularmachine.machine.recipe.search.RecipeCandidate;
import com.lw.modularmachine.machine.recipe.search.RecipePlanner;
import com.lw.modularmachine.machine.recipe.search.ResourcePlanningAdapter;
import com.lw.modularmachine.machine.structure.StructureService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceTransaction;
import com.lw.modularmachine.machine.recipe.search.RecipeResourceRequirements;
import net.minecraft.core.BlockPos;

/** Physical Chemical rules adapted into the shared execution stages. */
@ApiStatus.Internal
public final class ChemicalResourceExecution {
  private static final int TICK_OUTPUT_WORK = 64;
  private static final Map<StructureService.StructureSubject, TickOutputs> TICK_OUTPUTS = new WeakHashMap<>();

  private record TickRun(UUID id, int progress) {
  }

  private record TickTank(
      String part,
      BlockPos position,
      long revision,
      String selector,
      ChemicalResourceKey chemical,
      long amount,
      long capacity) {
  }

  private record TickOutputs(
      long structureRevision,
      long definitionEpoch,
      List<TickTank> tanks,
      List<TickRun> runs,
      List<RecipeCandidate.Output<ChemicalResourceKey>> claims,
      ChemicalOutputLayout.Planning plan,
      long lastWorkTick) {
  }

  private ChemicalResourceExecution() {
  }

  public static void clear() {
    TICK_OUTPUTS.clear();
  }

  public static RecipeResourceExecution.Validation<ChemicalRecipeData> validation() {
    return (data, registries) -> {
      if (!ChemicalPlatform.available()) {
        throw new IllegalArgumentException("Chemical recipes require the Mekanism integration");
      }
      for (var rows : List.of(data.inputs(), data.outputs(), data.perTick(), data.perTickOutputs())) {
        for (var requirement : rows) {
          if (!ChemicalPlatform.contains(requirement.chemical())) {
            throw new IllegalArgumentException("Unknown chemical " + requirement.chemical());
          }
        }
      }
    };
  }

  public static RecipeResourceExecution.Start<ChemicalRecipeData> start() {
    return inputs -> {
      var full = inputs.stream()
          .map(
              input -> new ChemicalTransactions.InputClaim(
                  input.ticket(),
                  ChemicalTransactions.scaled(input.data().inputs(), input.instances())))
          .toList();
      if (ChemicalTransactions.inputs(full) == null) {
        return null;
      }
      var actual = inputs.stream().map(input -> {
        var rows = ChemicalTransactions.scaled(input.data().inputs(), input.instances());
        if (input.run().isPresent()) {
          var run = input.run().orElseThrow();
          rows = ChemicalRecipeSampling
              .sample(rows, run.randomSeed(), run.parallelInstances(), RecipeModifier.Io.INPUT, -1);
        }

        return new ChemicalTransactions.InputClaim(input.ticket(), rows);
      }).toList();

      return ChemicalTransactions.inputs(actual);
    };
  }

  public static RecipeResourceExecution.Admission<ChemicalRecipeData> admission() {
    return input -> {
      // Per-tick amounts also have to fit the frozen representation of this run.
      ChemicalTransactions.scaled(input.data().perTick(), input.instances());
      ChemicalTransactions.scaled(input.data().perTickOutputs(), input.instances());
      var planning = new ChemicalOutputLayout(input.ticket()).plan(
          ChemicalTransactions
              .scaled(input.data().outputs().stream().filter(row -> row.chance() == 1).toList(), input.instances()));

      return new ResourcePlanningAdapter<>(planning::status, planning::advance);
    };
  }

  public static RecipeResourceExecution.Freeze<ChemicalRecipeData> freeze() {
    return (data, instances) -> new ChemicalRecipeData(
        ChemicalTransactions.scaled(data.inputs(), instances),
        ChemicalTransactions.scaled(data.outputs(), instances),
        ChemicalTransactions.scaled(data.perTick(), instances),
        ChemicalTransactions.scaled(data.perTickOutputs(), instances));
  }

  public static RecipeResourceExecution.Tick<ChemicalRecipeData> tick() {
    return new RecipeResourceExecution.Tick<>() {
      @Override
      public boolean required(ChemicalRecipeData data) {
        return !data.perTick().isEmpty() || !data.perTickOutputs().isEmpty();
      }

      @Override
      public RecipeResourceExecution.TickResult prepare(StructureService.StructureSubject owner, List<RecipeResourceExecution.Run<ChemicalRecipeData>> runs) {
        boolean inputsRequired = runs.stream().anyMatch(value -> !value.data().perTick().isEmpty());
        var captured = inputsRequired ? RecipeExecutionTicket.captureChemicalInputs(owner).orElse(null) : null;
        if (inputsRequired
            && (captured == null
                || runs.stream().filter(value -> !value.data().perTick().isEmpty())
                    .anyMatch(value -> !value.run().matchesInputGroups(captured)))) {
          return RecipeResourceExecution.TickResult.failed(RecipeResourceExecution.TickStatus.STALE);
        }
        var fullClaims = new ArrayList<ChemicalTransactions.InputClaim>();
        var actualClaims = new ArrayList<ChemicalTransactions.InputClaim>();
        var proofs = new ArrayList<RecipeExecutionTicket>();
        for (var value : runs) {
          if (value.data().perTick().isEmpty()) {
            continue;
          }
          var source = captured.forRun(value.run()).orElse(null);
          if (source == null) {
            return RecipeResourceExecution.TickResult.failed(RecipeResourceExecution.TickStatus.STALE);
          }
          if (ChemicalTransactions.missingInputs(source, value.data().perTick())) {
            return RecipeResourceExecution.TickResult.failed(RecipeResourceExecution.TickStatus.MISSING_INPUT);
          }
          proofs.add(source);
          fullClaims.add(new ChemicalTransactions.InputClaim(source, value.data().perTick()));
          actualClaims.add(
              new ChemicalTransactions.InputClaim(
                  source,
                  ChemicalRecipeSampling.sample(
                      value.data().perTick(),
                      value.run().randomSeed(),
                      value.run().parallelInstances(),
                      RecipeModifier.Io.INPUT,
                      value.run().progress())));
        }
        if (ChemicalTransactions.inputs(fullClaims) == null) {
          return RecipeResourceExecution.TickResult.failed(RecipeResourceExecution.TickStatus.INSUFFICIENT);
        }
        var batches = new ArrayList<ChemicalTransactions.Batch>();
        var inputBatch = ChemicalTransactions.inputs(actualClaims);
        if (inputBatch == null) {
          return RecipeResourceExecution.TickResult.failed(RecipeResourceExecution.TickStatus.STALE);
        }
        batches.add(inputBatch);
        var outputRuns = runs.stream()
            .filter(
                value -> (value.run().canEmitTickOutputs() || !value.run().triggeredResources().isEmpty())
                    && !value.data().perTickOutputs().isEmpty())
            .toList();
        if (!outputRuns.isEmpty()) {
          var outputs = RecipeExecutionTicket.captureOutputs(owner).orElse(null);
          if (outputs == null) {
            return RecipeResourceExecution.TickResult.failed(RecipeResourceExecution.TickStatus.STALE);
          }
          for (var value : outputRuns) {
            for (var row : value.data().perTickOutputs()) {
              if (!RecipeResourceRequirements.has(outputs, PortKind.CHEMICAL_OUTPUT, row.selector())) {
                return RecipeResourceExecution.TickResult.failed(RecipeResourceExecution.TickStatus.MISSING_OUTPUT);
              }
            }
          }
          var ids = outputRuns.stream().map(value -> new TickRun(value.run().runId(), value.run().progress())).toList();
          var claims = outputClaims(
              outputRuns.stream()
                  .map(
                      value -> ChemicalRecipeSampling.sample(
                          value.data().perTickOutputs(),
                          value.run().randomSeed(),
                          value.run().parallelInstances(),
                          RecipeModifier.Io.OUTPUT,
                          value.run().progress()))
                  .toList());
          var tanks = outputs.ports().stream().filter(port -> port.kind() == PortKind.CHEMICAL_OUTPUT)
              .map(
                  port -> new TickTank(
                      port.partId(),
                      port.position(),
                      port.revision(),
                      port.selector(),
                      port.chemical()
                          .isEmpty() ? null : new ChemicalResourceKey(port.chemical().chemical(), port.selector()),
                      port.chemical().amount(),
                      port.capacity()))
              .toList();
          var cached = TICK_OUTPUTS.get(owner);
          long now = outputs.level().getGameTime();
          if (cached == null
              || cached.structureRevision() != outputs.structureRevision()
              || cached.definitionEpoch() != outputs.definitionEpoch()
              || !cached.tanks().equals(tanks)
              || !cached.runs().equals(ids)
              || !cached.claims().equals(claims)
              || cached.plan().status() == RecipePlanner.Status.NO_MATCH && cached.lastWorkTick() != now) {
            cached = new TickOutputs(
                outputs.structureRevision(),
                outputs.definitionEpoch(),
                tanks,
                ids,
                claims,
                new ChemicalOutputLayout(outputs).planClaims(claims),
                cached == null ? Long.MIN_VALUE : cached.lastWorkTick());
            TICK_OUTPUTS.put(owner, cached);
          }
          if (cached.plan().status() == RecipePlanner.Status.BUDGET_EXHAUSTED && cached.lastWorkTick() != now) {
            cached.plan().advance(TICK_OUTPUT_WORK);
            cached = new TickOutputs(
                cached.structureRevision(),
                cached.definitionEpoch(),
                cached.tanks(),
                cached.runs(),
                cached.claims(),
                cached.plan(),
                now);
            TICK_OUTPUTS.put(owner, cached);
          }
          if (cached.plan().status() == RecipePlanner.Status.BUDGET_EXHAUSTED) {
            return RecipeResourceExecution.TickResult.failed(RecipeResourceExecution.TickStatus.STALE);
          }
          if (cached.plan().status() != RecipePlanner.Status.SELECTED) {
            return RecipeResourceExecution.TickResult.failed(RecipeResourceExecution.TickStatus.OUTPUT_BLOCKED);
          }
          proofs.add(outputs);
          batches.add(ChemicalTransactions.outputs(outputs, cached.plan().additions()));
        } else {
          TICK_OUTPUTS.remove(owner);
        }
        var transaction = new RecipeResourceTransaction();
        batches.forEach(batch -> transaction.phase().enlist(batch));

        return RecipeResourceExecution.TickResult.ready(new PreparedRecipeResource() {
          @Override
          public boolean current() {
            return proofs.stream().allMatch(RecipeCommitGuard::validate);
          }

          @Override
          public void addPatterns(PatternInputTransaction patterns) {
            batches.forEach(batch -> batch.addPatterns(patterns));
          }

          @Override
          public void acquire() {
            batches.forEach(ChemicalTransactions.Batch::acquire);
          }

          @Override
          public void resolve(boolean commit) {
            transaction.resolve(commit);
          }

          @Override
          public void publish() {
            batches.forEach(ChemicalTransactions.Batch::publish);
          }
        });
      }
    };
  }

  public static RecipeResourceExecution.Finish<ChemicalRecipeData> finish() {
    return (ticket, runs) -> {
      var claims = outputClaims(
          runs.stream()
              .map(
                  value -> ChemicalRecipeSampling.sample(
                      value.data().outputs(),
                      value.run().randomSeed(),
                      value.run().parallelInstances(),
                      RecipeModifier.Io.OUTPUT,
                      -1))
              .toList());
      var plan = new ChemicalOutputLayout(ticket).planClaims(claims);

      return new RecipeResourceExecution.Output() {
        @Override
        public Status status() {
          return convert(plan.status());
        }

        @Override
        public Progress advance(int workBudget) {
          if (plan.status() != RecipePlanner.Status.BUDGET_EXHAUSTED) {
            return new Progress(status(), 0);
          }
          var result = plan.advance(workBudget);

          return new Progress(convert(result.status()), result.workUsed());
        }

        @Override
        public PreparedRecipeResource prepared(RecipeExecutionTicket checked) {
          return ChemicalTransactions.outputs(checked, plan.additions());
        }
      };
    };
  }

  /**
   * Merge frozen runs before applying the planner's distinct requirement limit.
   */
  public static List<RecipeCandidate.Output<ChemicalResourceKey>> outputClaims(List<List<ChemicalRequirement>> outputs) {
    var totals = new LinkedHashMap<ChemicalResourceKey, Long>();
    for (var rows : outputs) {
      for (var output : rows) {
        totals.merge(new ChemicalResourceKey(output.chemical(), output.selector()), output.amount(), Math::addExact);
      }
    }

    return totals.entrySet().stream().map(entry -> new RecipeCandidate.Output<>(entry.getKey(), entry.getValue()))
        .toList();
  }

  private static RecipeResourcePlanning.Status convert(RecipePlanner.Status status) {
    return switch (status) {
      case SELECTED -> RecipeResourcePlanning.Status.READY;
      case NO_MATCH -> RecipeResourcePlanning.Status.REJECTED;
      case BUDGET_EXHAUSTED -> RecipeResourcePlanning.Status.PENDING;
    };
  }
}
