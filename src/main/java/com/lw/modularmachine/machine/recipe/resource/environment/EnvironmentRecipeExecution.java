package com.lw.modularmachine.machine.recipe.resource.environment;

import com.lw.modularmachine.api.recipe.EnvironmentRecipeData;
import com.lw.modularmachine.api.recipe.EnvironmentRequirement;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.recipe.resource.PreparedRecipeResource;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecution;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecutionRegistry;
import com.lw.modularmachine.machine.recipe.search.ResourcePlanningSequence;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.registry.MachineRegistries;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.ApiStatus;

/** Main-thread environment proof; it neither reserves nor writes resources. */
@ApiStatus.Internal
public final class EnvironmentRecipeExecution {
  private EnvironmentRecipeExecution() {
  }

  public static void register() {
    RecipeResourceExecutionRegistry.register(
        RecipeResourceDefinitions.ENVIRONMENT,
        input -> new ResourcePlanningSequence(List.of()),
        EnvironmentRecipeExecution::prepareStart,
        new RecipeResourceExecution.Tick<>() {
          @Override
          public boolean required(EnvironmentRecipeData data) {
            return !data.requirements().isEmpty();
          }

          @Override
          public RecipeResourceExecution.TickResult prepare(StructureService.StructureSubject owner, List<RecipeResourceExecution.Run<EnvironmentRecipeData>> runs) {
            return prepareTick(owner, runs);
          }
        },
        (ticket, runs) -> {
          throw new IllegalStateException("Environment conditions cannot produce outputs");
        },
        (data, instances) -> data,
        EnvironmentRecipeExecution::validate);
  }

  private static void validate(EnvironmentRecipeData data, HolderLookup.Provider registries) {
    for (var requirement : data.requirements()) {
      if (requirement instanceof EnvironmentRequirement.Biome biome) {
        for (var id : biome.biomes()) {
          if (registries.lookupOrThrow(Registries.BIOME).get(ResourceKey.create(Registries.BIOME, id)).isEmpty()) {
            throw new IllegalArgumentException("Unknown environment biome: " + id);
          }
        }
      }
    }
  }

  private static PreparedRecipeResource prepareStart(List<RecipeResourceExecution.Input<EnvironmentRecipeData>> inputs) {
    var ticket = inputs.getFirst().ticket();
    var proof = new Proof(ticket, inputs.stream().map(RecipeResourceExecution.Input::data).toList());
    return proof.matches() ? proof : null;
  }

  private static RecipeResourceExecution.TickResult prepareTick(StructureService.StructureSubject owner, List<RecipeResourceExecution.Run<EnvironmentRecipeData>> runs) {
    var ticket = RecipeExecutionTicket.captureComponents(owner).orElse(null);
    if (ticket == null) {
      return RecipeResourceExecution.TickResult.failed(RecipeResourceExecution.TickStatus.STALE);
    }
    var proof = new Proof(ticket, runs.stream().map(RecipeResourceExecution.Run::data).toList());
    if (positions(ticket).isEmpty()) {
      return RecipeResourceExecution.TickResult.failed(RecipeResourceExecution.TickStatus.MISSING_INPUT);
    }
    return proof.matches() ? RecipeResourceExecution.TickResult.ready(proof) : RecipeResourceExecution.TickResult
        .failed(RecipeResourceExecution.TickStatus.INSUFFICIENT);
  }

  public static List<BlockPos> positions(RecipeExecutionTicket ticket) {
    return ticket.componentPositions(MachineRegistries.ENVIRONMENT_HATCH.get());
  }

  public static boolean matches(ServerLevel level, List<BlockPos> positions, EnvironmentRecipeData data) {
    for (var requirement : data.requirements()) {
      if (positions.stream().noneMatch(position -> matches(level, position, requirement))) {
        return false;
      }
    }
    return true;
  }

  private static boolean matches(ServerLevel level, BlockPos position, EnvironmentRequirement requirement) {
    return switch (requirement) {
      case EnvironmentRequirement.Dimension dimension -> dimension.dimensions().contains(level.dimension().location());
      case EnvironmentRequirement.Biome biome ->
        level.getBiome(position).unwrapKey().map(ResourceKey::location).filter(biome.biomes()::contains).isPresent();
      case EnvironmentRequirement.Time time -> time.contains(level.getDayTime());
      case EnvironmentRequirement.MoonPhase moon -> level.isNight() && moon.moonphases().contains(level.getMoonPhase());
      case EnvironmentRequirement.Altitude altitude ->
        position.getY() >= altitude.begin() && position.getY() <= altitude.end();
      case EnvironmentRequirement.Weather weather -> switch (weather.weather()) {
        case SUNNY -> !level.isRaining() && !level.isThundering();
        case RAINING -> level.isRaining();
        case SNOWING -> (level.isRaining() || level.isThundering())
            && level.getBiome(position).value().getPrecipitationAt(position) == Biome.Precipitation.SNOW;
        case THUNDERING -> level.isThundering();
      };
    };
  }

  private record Proof(RecipeExecutionTicket ticket, List<EnvironmentRecipeData> declarations)
      implements
        PreparedRecipeResource {
    boolean matches() {
      var positions = positions(ticket);
      return declarations.stream()
          .allMatch(data -> EnvironmentRecipeExecution.matches(ticket.level(), positions, data));
    }

    @Override
    public boolean current() {
      return ticket.dependenciesCurrent() && matches();
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
