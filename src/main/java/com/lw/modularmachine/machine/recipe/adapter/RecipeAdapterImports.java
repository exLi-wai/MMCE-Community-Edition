package com.lw.modularmachine.machine.recipe.adapter;

import com.google.gson.JsonElement;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterContext;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterTags;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal, detached additions validated before one prospective publication.
 */
@ApiStatus.Internal
public final class RecipeAdapterImports {
  public record Generated(
      Map<ResourceLocation, MachineRecipe> recipes,
      Map<ResourceLocation, JsonElement> sources,
      long scriptRevision) {
    public Generated {
      recipes = Map.copyOf(recipes);
      sources = Map.copyOf(sources);
    }
  }

  private RecipeAdapterImports() {
  }

  public static Generated generate(RecipeManager manager, Map<ResourceLocation, MachineDefinition> machines, HolderLookup.Provider registries, RecipeAdapterTags tags) {
    RecipeAdapterRegistry.freeze();
    var scripts = RecipeAdapterScriptSources.snapshot();
    for (var machine : scripts.imports().keySet()) {
      if (!machines.containsKey(machine)) {
        throw new IllegalArgumentException("Unknown script recipe adapter machine: " + machine);
      }
    }
    var allSources = manager.getRecipes().stream().sorted((left, right) -> left.id().compareTo(right.id())).toList();
    var byId = new LinkedHashMap<ResourceLocation, RecipeHolder<?>>();
    allSources.forEach(source -> byId.put(source.id(), source));
    var recipes = new LinkedHashMap<ResourceLocation, MachineRecipe>();
    var declarations = new LinkedHashMap<ResourceLocation, JsonElement>();
    for (var machine : machines.values().stream().sorted((left, right) -> left.id().compareTo(right.id())).toList()) {
      var combined = new ArrayList<>(machine.recipeAdapters());
      combined.addAll(scripts.imports().getOrDefault(machine.id(), List.of()));
      RecipeAdapterDefinitions.validate(combined);
      for (var settings : combined) {
        if (!settings.allRecipes() && settings.recipes().isEmpty()) {
          continue;
        }
        var adapter = RecipeAdapterRegistry.resolve(settings.adapter());
        if (adapter.isEmpty()) {
          ModularMachine.LOGGER
              .info("Skipping recipe adapter {} for {}: required mod is not loaded", settings.adapter(), machine.id());
          continue;
        }
        List<RecipeHolder<?>> selected;
        if (settings.allRecipes()) {
          selected = allSources;
        } else {
          selected = new ArrayList<>(settings.recipes().size());
          for (var sourceId : settings.recipes()) {
            var source = byId.get(sourceId);
            if (source == null) {
              throw new IllegalArgumentException(machine.id() + ": Missing recipe adapter source " + sourceId);
            }
            selected.add(source);
          }
        }
        var context = new RecipeAdapterContext(machine.id(), settings, registries, selected, tags);
        var builders = adapter.get().adapt(context);
        if (builders == null) {
          throw new IllegalArgumentException(machine.id() + ": Recipe adapter returned a null declaration list");
        }
        for (var builder : builders) {
          var id = builder.id();
          if (!id.getPath().startsWith("mmcece/adapted/") || byId.containsKey(id) || recipes.containsKey(id)) {
            throw new IllegalArgumentException(
                id + ": Recipe adapter ID is outside its directory or collides with an existing recipe");
          }
          var declaration = builder.toJson(registries);
          var recipe = MachineRecipe.CODEC.codec()
              .parse(registries.createSerializationContext(JsonOps.INSTANCE), declaration).getOrThrow();
          if (!recipe.machineId().equals(machine.id())) {
            throw new IllegalArgumentException(id + ": Recipe adapter changed the target machine");
          }
          recipes.put(id, recipe);
          declarations.put(id, declaration.deepCopy());
        }
      }
    }
    if (!RecipeAdapterScriptSources.isCurrent(scripts.revision())) {
      throw new IllegalStateException("Recipe adapter scripts changed during import preparation");
    }
    return new Generated(recipes, declarations, scripts.revision());
  }
}
