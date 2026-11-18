package com.lw.modularmachine.machine.recipe.resource.world;

import com.lw.modularmachine.api.recipe.MeteorRecipeData;
import com.lw.modularmachine.api.recipe.MeteorRecipeData.Launch;
import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning.Status;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.recipe.resource.PreparedRecipeResource;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecution;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecutionRegistry;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceTransaction;
import com.lw.modularmachine.machine.recipe.search.CandidateRecipeResource;
import com.lw.modularmachine.machine.recipe.search.RecipeCatalog;
import com.lw.modularmachine.machine.recipe.search.RecipeSearchResource;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.util.Mods;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class MeteorExecution {
  private MeteorExecution() {
  }

  public static void register() {
    RecipeResourceExecutionRegistry.register(RecipeResourceDefinitions.METEOR, input -> {
      var claim = input.data().output().orElseThrow();
      return (long) claim.count() * input.instances() <= Launch.MAX_LAUNCHES ? plan(
          input.ticket(),
          input.data().scaled(input.instances()).output().stream()
              .toList()) : new OutputPlan(Status.REJECTED, List.of());
    }, inputs -> new Batch(Map.of()), new RecipeResourceExecution.Tick<>() {
      @Override
      public boolean required(MeteorRecipeData data) {
        return false;
      }

      @Override
      public RecipeResourceExecution.TickResult prepare(StructureService.StructureSubject owner, List<RecipeResourceExecution.Run<MeteorRecipeData>> runs) {
        throw new IllegalStateException("Meteor launches are completion outputs");
      }
    },
        (ticket, runs) -> plan(ticket, runs.stream().flatMap(run -> run.data().output().stream()).toList()),
        MeteorRecipeData::scaled,
        (data, registries) -> {
          if (!Mods.NEO_VITAE.isLoaded()) {
            throw new IllegalArgumentException("Meteor output requires Neo Vitae");
          }
          var catalyst = data.output().orElseThrow().catalyst();
          if (BuiltInRegistries.ITEM.getOptional(catalyst).filter(item -> item != Items.AIR).isEmpty()) {
            throw new IllegalArgumentException("Unknown meteor catalyst: " + catalyst);
          }
        });
  }

  public static RecipeSearchResource search(RecipeCatalog catalog) {
    return new CandidateRecipeResource<>(
        catalog,
        RecipeResourceDefinitions.METEOR,
        (ticket, data) -> true,
        (ticket, data) -> plan(ticket, data.output().stream().toList()));
  }

  private static OutputPlan plan(RecipeExecutionTicket ticket, List<Launch> launches) {
    return new OutputPlan(allocate(ticket, launches) == null ? Status.REJECTED : Status.READY, launches);
  }

  private static Batch allocate(RecipeExecutionTicket ticket, List<Launch> launches) {
    if (launches.isEmpty()) {
      return new Batch(Map.of());
    }
    var block = MachineRegistries.METEOR_OUTPUT;
    if (block == null || launches.stream().mapToLong(Launch::count).sum() > Launch.MAX_LAUNCHES) {
      return null;
    }
    var placements = new LinkedHashMap<MeteorPort, ResourceLocation>();
    var positions = ticket.componentPositions(block.get());
    for (var launch : launches) {
      int needed = launch.count();
      for (var position : positions) {
        if (ticket.level().getBlockEntity(position) instanceof MeteorPort port
            && !placements.containsKey(port)
            && port.available(launch.catalyst())) {
          placements.put(port, launch.catalyst());
          if (--needed == 0) {
            break;
          }
        }
      }
      if (needed != 0) {
        return null;
      }
    }
    return new Batch(placements);
  }

  private record OutputPlan(Status status, List<Launch> launches) implements RecipeResourceExecution.Output {
    @Override
    public Progress advance(int budget) {
      return new Progress(status, 0);
    }

    @Override
    public PreparedRecipeResource prepared(RecipeExecutionTicket ticket) {
      return status == Status.READY ? allocate(ticket, launches) : null;
    }
  }

  private record Batch(Map<MeteorPort, ResourceLocation> placements, RecipeResourceTransaction transaction)
      implements
        PreparedRecipeResource {
    private Batch(Map<MeteorPort, ResourceLocation> placements) {
      this(placements, new RecipeResourceTransaction());
    }

    @Override
    public boolean current() {
      return placements.entrySet().stream().allMatch(entry -> entry.getKey().available(entry.getValue()));
    }

    @Override
    public void acquire() {
      var phase = transaction.phase();
      placements.forEach((port, catalyst) -> phase.enlist(port.acquire(catalyst)));
    }

    @Override
    public void resolve(boolean commit) {
      transaction.resolve(commit);
    }

    @Override
    public void publish() {
      placements.keySet().forEach(MeteorPort::publishLaunch);
    }
  }
}
