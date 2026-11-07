package com.lw.modularmachine.api.recipe;

import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.MachineRecipeRegistries;
import com.lw.modularmachine.machine.recipe.MachineRecipeService;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

/** Read-only access to the currently published machine recipe definitions. */
public final class MachineRecipeApi {
  /**
   * A query-time view; it does not authorize production and may age across
   * reloads.
   */
  public record Definition(ResourceLocation id, MachineRecipe recipe) {
    public Definition {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(recipe, "recipe");
    }
  }

  private MachineRecipeApi() {
  }

  /** Returns empty while the server has no current, validated recipe catalog. */
  public static Optional<Definition> find(MinecraftServer server, ResourceLocation recipeId) {
    Objects.requireNonNull(server, "server");
    Objects.requireNonNull(recipeId, "recipeId");
    return MachineRecipeService.definition(server, recipeId).map(recipe -> new Definition(recipeId, recipe));
  }

  /**
   * Lists the currently published definitions for one machine in stable ID order.
   * This never loads a chunk or reads a controller.
   */
  public static List<Definition> forMachine(MinecraftServer server, ResourceLocation machineId) {
    Objects.requireNonNull(server, "server");
    Objects.requireNonNull(machineId, "machineId");
    ServerThreadGuard.require(server, "Machine recipe API query");
    var result = new ArrayList<Definition>();
    for (var holder : server.getRecipeManager().getAllRecipesFor(MachineRecipeRegistries.ITEM_TYPE.get())) {
      if (!holder.value().machineId().equals(machineId)) {
        continue;
      }
      find(server, holder.id()).ifPresent(result::add);
    }
    result.sort((left, right) -> left.id().compareTo(right.id()));

    return List.copyOf(result);
  }
}
