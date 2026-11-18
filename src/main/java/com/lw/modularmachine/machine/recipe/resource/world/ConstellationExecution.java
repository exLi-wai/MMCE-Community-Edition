package com.lw.modularmachine.machine.recipe.resource.world;

import com.lw.modularmachine.api.recipe.ConstellationRecipeData;
import com.lw.modularmachine.integration.astral.StarlightHatch;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.recipe.resource.PreparedRecipeResource;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecution;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecutionRegistry;
import com.lw.modularmachine.machine.recipe.search.CandidateRecipeResource;
import com.lw.modularmachine.machine.recipe.search.RecipeCatalog;
import com.lw.modularmachine.machine.recipe.search.RecipeSearchResource;
import com.lw.modularmachine.machine.recipe.search.ResourcePlanningSequence;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.util.Mods;
import java.util.List;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class ConstellationExecution {
  private ConstellationExecution() {
  }

  public static void register() {
    RecipeResourceExecutionRegistry
        .register(RecipeResourceDefinitions.CONSTELLATION, input -> new ResourcePlanningSequence(List.of()), inputs -> {
          var proof = new Proof(
              inputs.getFirst().ticket(),
              inputs.stream().map(RecipeResourceExecution.Input::data).toList());
          return proof.current() ? proof : null;
        }, new RecipeResourceExecution.Tick<>() {
          @Override
          public boolean required(ConstellationRecipeData data) {
            return !data.active().isEmpty();
          }

          @Override
          public RecipeResourceExecution.TickResult prepare(StructureService.StructureSubject owner, List<RecipeResourceExecution.Run<ConstellationRecipeData>> runs) {
            var ticket = RecipeExecutionTicket.captureComponents(owner).orElse(null);
            if (ticket == null) {
              return RecipeResourceExecution.TickResult.failed(RecipeResourceExecution.TickStatus.STALE);
            }
            var proof = new Proof(ticket, runs.stream().map(RecipeResourceExecution.Run::data).toList());
            return proof.current() ? RecipeResourceExecution.TickResult.ready(
                proof) : RecipeResourceExecution.TickResult.failed(RecipeResourceExecution.TickStatus.INSUFFICIENT);
          }
        }, (ticket, runs) -> {
          throw new IllegalStateException("Constellation conditions have no outputs");
        }, (data, instances) -> data, (data, registries) -> {
          if (!Mods.ASTRAL_SORCERY.isLoaded()) {
            throw new IllegalArgumentException("Constellation conditions require Astral Sorcery");
          }
          data.active().forEach(id -> {
            if (StarlightHatch.constellation(id) == null) {
              throw new IllegalArgumentException("Unknown constellation: " + id);
            }
          });
        });
  }

  public static RecipeSearchResource search(RecipeCatalog catalog) {
    return new CandidateRecipeResource<>(
        catalog,
        RecipeResourceDefinitions.CONSTELLATION,
        ConstellationExecution::matches,
        (ticket, data) -> new ResourcePlanningSequence(List.of()));
  }

  private static boolean matches(RecipeExecutionTicket ticket, ConstellationRecipeData data) {
    if (data.active().isEmpty()) {
      return true;
    }
    var block = MachineRegistries.CONSTELLATION;
    return block != null
        && !ticket.componentPositions(block.get()).isEmpty()
        && data.active().stream().allMatch(id -> StarlightHatch.active(ticket.level(), id));
  }

  private record Proof(RecipeExecutionTicket ticket, List<ConstellationRecipeData> conditions)
      implements
        PreparedRecipeResource {
    @Override
    public boolean current() {
      return ticket.dependenciesCurrent() && conditions.stream().allMatch(condition -> matches(ticket, condition));
    }

    @Override
    public void acquire() {
    }

    @Override
    public void resolve(boolean commit) {
    }

    @Override
    public void publish() {
    }
  }
}
