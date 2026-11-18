package com.lw.modularmachine.machine.recipe.resource.world;

import com.lw.modularmachine.api.recipe.WorldPoolRecipeData;
import com.lw.modularmachine.api.recipe.WorldPoolRecipeData.Claim;
import com.lw.modularmachine.api.recipe.resource.RecipeResourceType;
import com.lw.modularmachine.integration.neovitae.SpiritusPoolAccess;
import com.lw.modularmachine.integration.naturesaura.NaturalAuraPoolAccess;
import com.lw.modularmachine.integration.thaumaturge.ThaumaturgePoolAccess;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.util.Mods;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class WorldPoolResources {
  private static final Set<String> WILL_TYPES = Set.of("raw", "ruina", "nihilum", "invictus", "vindicta");

  public static final WorldPoolExecution AURA = new WorldPoolExecution(
      RecipeResourceDefinitions.AURA_WORLD,
      new WorldPoolExecution.Blocks((output, multichunk) -> output ? block(MachineRegistries.AURA_OUTPUT) : null),
      () -> Mods.NATURES_AURA.isLoaded() ? new NaturalAuraPoolAccess() : null);
  public static final WorldPoolExecution WILL = new WorldPoolExecution(
      RecipeResourceDefinitions.WILL,
      new WorldPoolExecution.Blocks(
          (output, multichunk) -> block(
              multichunk ? (output ? MachineRegistries.MULTI_WILL_OUTPUT : MachineRegistries.MULTI_WILL_INPUT) : (output ? MachineRegistries.WILL_OUTPUT : MachineRegistries.WILL_INPUT))),
      () -> Mods.NEO_VITAE.isLoaded() ? new SpiritusPoolAccess() : null);
  public static final WorldPoolExecution VIS = thaumaturge(
      RecipeResourceDefinitions.VIS,
      false,
      () -> block(MachineRegistries.VIS_INPUT),
      () -> block(MachineRegistries.VIS_OUTPUT));
  public static final WorldPoolExecution FLUX = thaumaturge(
      RecipeResourceDefinitions.FLUX,
      true,
      () -> block(MachineRegistries.FLUX_INPUT),
      () -> block(MachineRegistries.FLUX_OUTPUT));

  private WorldPoolResources() {
  }

  public static void register() {
    AURA.register(Mods.NATURES_AURA, data -> {
      if (data.input().isPresent()) {
        throw new IllegalArgumentException("World aura only supports output");
      }
      data.output().ifPresent(claim -> {
        var id = ResourceLocation.tryParse(claim.kind());
        if (id == null
            || !NaturalAuraPoolAccess.knownType(id)
            || claim.multichunk()
            || claim.radius() != 0
            || claim.limit() <= 0
            || claim.limit() > Integer.MAX_VALUE) {
          throw new IllegalArgumentException("Natural aura output requires an aura type and a positive int limit");
        }
      });
    });
    WILL.register(Mods.NEO_VITAE, data -> {
      data.input().ifPresent(WorldPoolResources::validateWill);
      data.output().ifPresent(WorldPoolResources::validateWill);
    });
    VIS.register(Mods.THAUMATURGE, WorldPoolResources::validateThaumaturge);
    FLUX.register(Mods.THAUMATURGE, WorldPoolResources::validateThaumaturge);
  }

  private static WorldPoolExecution thaumaturge(RecipeResourceType<WorldPoolRecipeData> type, boolean flux, Supplier<Block> input, Supplier<Block> output) {
    return new WorldPoolExecution(
        type,
        new WorldPoolExecution.Blocks((writing, ignored) -> writing ? output.get() : input.get()),
        () -> Mods.THAUMATURGE.isLoaded() ? new ThaumaturgePoolAccess(flux) : null);
  }

  private static void validateThaumaturge(WorldPoolRecipeData data) {
    data.input().ifPresent(WorldPoolResources::validateThaumaturge);
    data.output().ifPresent(WorldPoolResources::validateThaumaturge);
  }

  private static void validateWill(Claim claim) {
    if (!WILL_TYPES.contains(claim.kind()) || !claim.multichunk() && claim.radius() != 0) {
      throw new IllegalArgumentException("Invalid Neo Vitae will type or single-chunk radius");
    }
  }

  private static void validateThaumaturge(Claim claim) {
    if (!claim.kind().isEmpty() || claim.multichunk() || claim.limit() > 500_000) {
      throw new IllegalArgumentException(
          "Invalid Vis/Flux claim; radius uses its own hatch and limit is at most 500000 milliunits");
    }
  }

  private static Block block(Supplier<? extends Block> block) {
    return block == null ? null : block.get();
  }
}
