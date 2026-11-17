package com.lw.modularmachine.machine.recipe.adapter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterContext;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterImport;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterTags;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.MachineRecipeJson;
import com.lw.modularmachine.machine.recipe.MachineRecipeReloadListener;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
    return generate(manager, machines, registries, tags, Map.of());
  }

  public static Generated generate(RecipeManager manager, Map<ResourceLocation, MachineDefinition> machines, HolderLookup.Provider registries, RecipeAdapterTags tags, Map<ResourceLocation, JsonElement> originalSources) {
    RecipeAdapterRegistry.freeze();
    var scripts = RecipeAdapterScriptSources.snapshot();
    for (var machine : scripts.imports().keySet()) {
      if (!machines.containsKey(machine)) {
        throw new IllegalArgumentException("Unknown script recipe adapter machine: " + machine);
      }
    }
    var imports = combinedImports(machines, scripts.imports());
    var generation = new Generation(manager, registries, tags, machines.keySet());
    generation.captureMachineSources(imports, originalSources);
    for (var machineId : RecipeAdapterDependencies.order(imports)) {
      for (var settings : imports.get(machineId)) {
        generation.generate(machines.get(machineId), settings);
      }
    }
    if (!RecipeAdapterScriptSources.isCurrent(scripts.revision())) {
      throw new IllegalStateException("Recipe adapter scripts changed during import preparation");
    }
    return new Generated(generation.recipes, generation.declarations, scripts.revision());
  }

  private static Map<ResourceLocation, List<RecipeAdapterImport>> combinedImports(Map<ResourceLocation, MachineDefinition> machines, Map<ResourceLocation, List<RecipeAdapterImport>> scripts) {
    var imports = new LinkedHashMap<ResourceLocation, List<RecipeAdapterImport>>();
    for (var machine : machines.values()) {
      var combined = new ArrayList<>(machine.recipeAdapters());
      combined.addAll(scripts.getOrDefault(machine.id(), List.of()));
      RecipeAdapterDefinitions.validate(combined);
      imports.put(machine.id(), List.copyOf(combined));
    }
    return imports;
  }

  /** One unpublished generation owns its validated outputs and source indexes. */
  private static final class Generation {
    private final HolderLookup.Provider registries;
    private final RecipeAdapterTags tags;
    private final List<RecipeHolder<?>> originalRecipes;
    private final Map<ResourceLocation, RecipeHolder<?>> originalsById = new LinkedHashMap<>();
    private final Map<ResourceLocation, MachineRecipe> recipes = new LinkedHashMap<>();
    private final Map<ResourceLocation, JsonElement> declarations = new LinkedHashMap<>();
    private final Map<ResourceLocation, Map<ResourceLocation, JsonElement>> machineSources = new LinkedHashMap<>();

    private Generation(RecipeManager manager, HolderLookup.Provider registries, RecipeAdapterTags tags, Set<ResourceLocation> machines) {
      this.registries = registries;
      this.tags = tags;
      originalRecipes = manager.getRecipes().stream().sorted((left, right) -> left.id().compareTo(right.id())).toList();
      originalRecipes.forEach(source -> originalsById.put(source.id(), source));
      machines.forEach(machine -> machineSources.put(machine, new LinkedHashMap<>()));
    }

    private void captureMachineSources(Map<ResourceLocation, List<RecipeAdapterImport>> imports, Map<ResourceLocation, JsonElement> originalSources) {
      var copiedMachines = imports.values().stream().flatMap(List::stream).map(RecipeAdapterImport::adapter)
          .filter(adapter -> !RecipeAdapterRegistry.known(adapter)).collect(Collectors.toSet());
      for (var source : originalRecipes) {
        if (!(source.value() instanceof MachineRecipe recipe) || !machineSources.containsKey(recipe.machineId())) {
          continue;
        }
        var original = originalSources.get(source.id());
        if (copiedMachines.contains(recipe.machineId()) && original == null) {
          throw new IllegalArgumentException(
              source.id() + ": Machine recipe copies require an original validated declaration");
        }
        if (original != null) {
          machineSources.get(recipe.machineId()).put(source.id(), original.deepCopy());
        }
      }
    }

    private void generate(MachineDefinition machine, RecipeAdapterImport settings) {
      if (!settings.allRecipes() && settings.recipes().isEmpty()) {
        return;
      }
      if (RecipeAdapterRegistry.known(settings.adapter())) {
        adaptRecipes(machine, settings);
      } else {
        copyMachineRecipes(machine, settings);
      }
    }

    private void copyMachineRecipes(MachineDefinition machine, RecipeAdapterImport settings) {
      var sourceDeclarations = machineSources.get(settings.adapter());
      var selectedIds = settings.allRecipes() ? sourceDeclarations.keySet().stream().sorted().toList() : settings
          .recipes();
      var context = new RecipeAdapterContext(machine.id(), settings, registries, List.of(), tags);
      for (var sourceId : selectedIds) {
        var source = sourceDeclarations.get(sourceId);
        if (source == null || !source.isJsonObject()) {
          throw new IllegalArgumentException(machine.id() + ": Missing machine recipe adapter source " + sourceId);
        }
        var id = context.importedId(sourceId, "");
        var declaration = MachineRecipeCopies.copy(source.getAsJsonObject(), machine.id(), settings);
        addGenerated(id, declaration, machine.id());
      }
    }

    private void adaptRecipes(MachineDefinition machine, RecipeAdapterImport settings) {
      var adapter = RecipeAdapterRegistry.resolve(settings.adapter());
      if (adapter.isEmpty()) {
        ModularMachine.LOGGER
            .info("Skipping recipe adapter {} for {}: required mod is not loaded", settings.adapter(), machine.id());
        return;
      }
      var selected = selectedRecipes(machine.id(), settings);
      var context = new RecipeAdapterContext(machine.id(), settings, registries, selected, tags);
      var builders = adapter.get().adapt(context);
      if (builders == null) {
        throw new IllegalArgumentException(machine.id() + ": Recipe adapter returned a null declaration list");
      }
      for (var builder : builders) {
        var id = builder.id();
        var declaration = RecipeAdapterCustomizations.apply(builder.toJson(registries), settings.customization());
        addGenerated(id, declaration, machine.id());
      }
    }

    private List<RecipeHolder<?>> selectedRecipes(ResourceLocation machine, RecipeAdapterImport settings) {
      if (settings.allRecipes()) {
        return originalRecipes;
      }
      var selected = new ArrayList<RecipeHolder<?>>(settings.recipes().size());
      for (var sourceId : settings.recipes()) {
        var source = originalsById.get(sourceId);
        if (source == null) {
          throw new IllegalArgumentException(machine + ": Missing recipe adapter source " + sourceId);
        }
        selected.add(source);
      }
      return selected;
    }

    private void addGenerated(ResourceLocation id, JsonObject declaration, ResourceLocation machine) {
      if (!id.getPath().startsWith("mmcece/adapted/") || originalsById.containsKey(id) || recipes.containsKey(id)) {
        throw new IllegalArgumentException(
            id + ": Recipe adapter ID is outside its directory or collides with an existing recipe");
      }
      if (recipes.size() >= MachineRecipeReloadListener.MAX_FILES) {
        throw new IllegalArgumentException("Too many generated machine recipes");
      }
      MachineRecipeJson.validate(declaration);
      var recipe = MachineRecipe.CODEC.codec()
          .parse(registries.createSerializationContext(JsonOps.INSTANCE), declaration).getOrThrow();
      if (!recipe.machineId().equals(machine)) {
        throw new IllegalArgumentException(id + ": Recipe adapter changed the target machine");
      }
      recipes.put(id, recipe);
      declarations.put(id, declaration.deepCopy());
      machineSources.get(machine).put(id, declaration.deepCopy());
    }
  }
}
