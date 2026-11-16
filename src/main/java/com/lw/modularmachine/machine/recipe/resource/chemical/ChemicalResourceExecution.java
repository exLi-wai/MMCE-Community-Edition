package com.lw.modularmachine.machine.recipe.resource.chemical;

import com.lw.modularmachine.api.recipe.ChemicalRecipeData;
import com.lw.modularmachine.api.recipe.ChemicalRequirement;
import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import com.lw.modularmachine.machine.recipe.ChemicalTransactions;
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
import org.jetbrains.annotations.ApiStatus;

/** Physical Chemical rules adapted into the shared execution stages. */
@ApiStatus.Internal
public final class ChemicalResourceExecution {
  private ChemicalResourceExecution() {
  }

  public static RecipeResourceExecution.Validation<ChemicalRecipeData> validation() {
    return (data, registries) -> {
      if (!ChemicalPlatform.available()) {
        throw new IllegalArgumentException("Chemical recipes require the Mekanism integration");
      }
      for (var rows : List.of(data.inputs(), data.outputs(), data.perTick())) {
        for (var requirement : rows) {
          if (!ChemicalPlatform.contains(requirement.chemical())) {
            throw new IllegalArgumentException("Unknown chemical " + requirement.chemical());
          }
        }
      }
    };
  }

  public static RecipeResourceExecution.Start<ChemicalRecipeData> start() {
    return inputs -> ChemicalTransactions.inputs(
        inputs.stream()
            .map(
                input -> new ChemicalTransactions.InputClaim(
                    input.ticket(),
                    ChemicalTransactions.scaled(input.data().inputs(), input.instances())))
            .toList());
  }

  public static RecipeResourceExecution.Admission<ChemicalRecipeData> admission() {
    return input -> {
      // Per-tick amounts also have to fit the frozen representation of this run.
      ChemicalTransactions.scaled(input.data().perTick(), input.instances());
      var planning = new ChemicalOutputLayout(input.ticket())
          .plan(ChemicalTransactions.scaled(input.data().outputs(), input.instances()));

      return new ResourcePlanningAdapter<>(planning::status, planning::advance);
    };
  }

  public static RecipeResourceExecution.Freeze<ChemicalRecipeData> freeze() {
    return (data, instances) -> new ChemicalRecipeData(
        ChemicalTransactions.scaled(data.inputs(), instances),
        ChemicalTransactions.scaled(data.outputs(), instances),
        ChemicalTransactions.scaled(data.perTick(), instances));
  }

  public static RecipeResourceExecution.Tick<ChemicalRecipeData> tick() {
    return new RecipeResourceExecution.Tick<>() {
      @Override
      public boolean required(ChemicalRecipeData data) {
        return !data.perTick().isEmpty();
      }

      @Override
      public RecipeResourceExecution.TickResult prepare(StructureService.StructureSubject owner, List<RecipeResourceExecution.Run<ChemicalRecipeData>> runs) {
        var captured = RecipeExecutionTicket.captureChemicalInputs(owner).orElse(null);
        if (captured == null || runs.stream().anyMatch(value -> !value.run().matchesInputGroups(captured))) {
          return RecipeResourceExecution.TickResult.failed(RecipeResourceExecution.TickStatus.STALE);
        }
        var claims = new ArrayList<ChemicalTransactions.InputClaim>();
        var proofs = new ArrayList<RecipeExecutionTicket>();
        for (var value : runs) {
          var source = captured.forRun(value.run()).orElse(null);
          if (source == null) {
            return RecipeResourceExecution.TickResult.failed(RecipeResourceExecution.TickStatus.STALE);
          }
          if (ChemicalTransactions.missingInputs(source, value.data().perTick())) {
            return RecipeResourceExecution.TickResult.failed(RecipeResourceExecution.TickStatus.MISSING_INPUT);
          }
          proofs.add(source);
          claims.add(new ChemicalTransactions.InputClaim(source, value.data().perTick()));
        }
        var batch = ChemicalTransactions.inputs(claims);
        if (batch == null) {
          return RecipeResourceExecution.TickResult.failed(RecipeResourceExecution.TickStatus.INSUFFICIENT);
        }

        return RecipeResourceExecution.TickResult.ready(new PreparedRecipeResource() {
          @Override
          public boolean current() {
            return proofs.stream().allMatch(RecipeCommitGuard::validate);
          }

          @Override
          public void addPatterns(PatternInputTransaction patterns) {
            batch.addPatterns(patterns);
          }

          @Override
          public void acquire() {
            batch.acquire();
          }

          @Override
          public void resolve(boolean commit) {
            batch.resolve(commit);
          }

          @Override
          public void publish() {
            batch.publish();
          }
        });
      }
    };
  }

  public static RecipeResourceExecution.Finish<ChemicalRecipeData> finish() {
    return (ticket, runs) -> {
      var claims = outputClaims(runs.stream().map(run -> run.data().outputs()).toList());
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
