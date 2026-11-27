package com.lw.modularmachine.machine.recipe.adapter;

import com.lw.modularmachine.api.recipe.MachineRecipeDeclaration;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterContext;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterImport;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterTags;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.MachineRecipeCompiler;
import com.lw.modularmachine.machine.recipe.MachineRecipeReloadListener;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
      Map<ResourceLocation, MachineRecipeDeclaration> sources,
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

  public static Generated generate(RecipeManager manager, Map<ResourceLocation, MachineDefinition> machines, HolderLookup.Provider registries, RecipeAdapterTags tags, Map<ResourceLocation, MachineRecipeDeclaration> originalSources) {
    return generate(manager.getRecipes(), machines, registries, tags, originalSources, Set.of(), Map.of());
  }

  public static Generated generate(Collection<RecipeHolder<?>> candidates, Map<ResourceLocation, MachineDefinition> machines, HolderLookup.Provider registries, RecipeAdapterTags tags, Map<ResourceLocation, MachineRecipeDeclaration> originalSources, Set<ResourceLocation> omittedIds, Map<ResourceLocation, Set<ResourceLocation>> omittedMachines) {
    var unattributed = new LinkedHashSet<>(omittedIds);
    unattributed.removeAll(omittedMachines.keySet());
    return generate(candidates, machines, registries, tags, originalSources, omittedIds, omittedMachines, unattributed);
  }

  public static Generated generate(Collection<RecipeHolder<?>> candidates, Map<ResourceLocation, MachineDefinition> machines, HolderLookup.Provider registries, RecipeAdapterTags tags, Map<ResourceLocation, MachineRecipeDeclaration> originalSources, Set<ResourceLocation> omittedIds, Map<ResourceLocation, Set<ResourceLocation>> omittedMachines, Set<ResourceLocation> unattributed) {
    RecipeAdapterRegistry.freeze();
    var scripts = RecipeAdapterScriptSources.snapshot();
    for (var machine : scripts.imports().keySet()) {
      if (!machines.containsKey(machine)) {
        throw new IllegalArgumentException("Unknown script recipe adapter machine: " + machine);
      }
    }
    var imports = combinedImports(machines, scripts.imports());
    var generation = new Generation(candidates, registries, tags, machines.keySet(), omittedIds);
    generation.captureMachineSources(imports, originalSources);
    var order = RecipeAdapterDependencies.order(imports);
    generation.captureOmissions(imports, order, omittedMachines, unattributed);
    for (var machineId : order) {
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
    private final Set<ResourceLocation> omittedIds;
    private final Map<ResourceLocation, RecipeHolder<?>> originalsById = new LinkedHashMap<>();
    private final Map<ResourceLocation, MachineRecipe> recipes = new LinkedHashMap<>();
    private final Map<ResourceLocation, MachineRecipeDeclaration> declarations = new LinkedHashMap<>();
    private final Map<ResourceLocation, Map<ResourceLocation, MachineRecipeDeclaration>> machineSources = new LinkedHashMap<>();
    private final Map<ResourceLocation, Set<ResourceLocation>> omittedMachineSources = new LinkedHashMap<>();
    private final Map<ResourceLocation, Set<ResourceLocation>> explicitlySelected = new LinkedHashMap<>();
    private final Set<ResourceLocation> neededByCopyAll = new LinkedHashSet<>();
    private int retainedOmittedCopies;

    private Generation(Collection<RecipeHolder<?>> candidates, HolderLookup.Provider registries, RecipeAdapterTags tags, Set<ResourceLocation> machines, Set<ResourceLocation> omittedIds) {
      this.registries = registries;
      this.tags = tags;
      this.omittedIds = omittedIds;
      originalRecipes = candidates.stream().sorted((left, right) -> left.id().compareTo(right.id())).toList();
      originalRecipes.forEach(source -> originalsById.put(source.id(), source));
      machines.forEach(machine -> machineSources.put(machine, new LinkedHashMap<>()));
    }

    private void captureMachineSources(Map<ResourceLocation, List<RecipeAdapterImport>> imports, Map<ResourceLocation, MachineRecipeDeclaration> originalSources) {
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
          machineSources.get(recipe.machineId()).put(source.id(), original);
        }
      }
    }

    private void captureOmissions(Map<ResourceLocation, List<RecipeAdapterImport>> imports, List<ResourceLocation> order, Map<ResourceLocation, Set<ResourceLocation>> omittedMachines, Set<ResourceLocation> unattributed) {
      if (omittedIds.isEmpty() && omittedMachines.isEmpty()) {
        return;
      }
      // A surviving recipe with this ID can belong to a different machine.
      omittedMachines.forEach((id, owners) -> owners.forEach(machine -> {
        if (machineSources.containsKey(machine) && !machineSources.get(machine).containsKey(id)) {
          omittedMachineSources.computeIfAbsent(machine, key -> new LinkedHashSet<>()).add(id);
        }
      }));
      // Follow copies back from explicit selections. Copy-all chains without a
      // downstream selection only need the recipes that were actually generated.
      for (var machine : order.reversed()) {
        for (var settings : imports.get(machine)) {
          var sourceMachine = settings.adapter();
          if (RecipeAdapterRegistry.known(sourceMachine)) {
            continue;
          }
          if (settings.allRecipes()) {
            if (needsOmittedCopies(machine)) {
              neededByCopyAll.add(sourceMachine);
            }
            continue;
          }
          if (settings.recipes().isEmpty()) {
            continue;
          }
          explicitlySelected.computeIfAbsent(sourceMachine, key -> new LinkedHashSet<>()).addAll(settings.recipes());
          // An explicit source supplies the scope of an unattributed declaration.
          for (var id : settings.recipes()) {
            if (unattributed.contains(id)) {
              omittedMachineSources.computeIfAbsent(sourceMachine, key -> new LinkedHashSet<>()).add(id);
            }
          }
        }
      }
    }

    private boolean needsOmittedCopies(ResourceLocation machine) {
      return neededByCopyAll.contains(machine) || explicitlySelected.containsKey(machine);
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
      var omittedSources = omittedMachineSources.getOrDefault(settings.adapter(), Set.of());
      var selectedIds = settings.allRecipes() ? sourceDeclarations.keySet().stream().sorted().toList() : settings
          .recipes();
      var context = new RecipeAdapterContext(machine.id(), settings, registries, List.of(), tags);
      if (settings.allRecipes() && needsOmittedCopies(machine.id())) {
        for (var sourceId : omittedSources) {
          if (!sourceDeclarations.containsKey(sourceId)) {
            omitMachineCopy(context, sourceId);
          }
        }
      }
      var additions = new RecipeAdapterCustomizations(settings.customization());
      for (var sourceId : selectedIds) {
        var source = sourceDeclarations.get(sourceId);
        if (source == null && omittedSources.contains(sourceId)) {
          omitMachineCopy(context, sourceId);
          continue;
        }
        if (source == null) {
          throw new IllegalArgumentException(machine.id() + ": Missing machine recipe adapter source " + sourceId);
        }
        var id = context.importedId(sourceId, "");
        var declaration = MachineRecipeCopies.copy(source, machine.id(), settings, additions);
        addGenerated(id, declaration, machine.id());
      }
    }

    private void omitMachineCopy(RecipeAdapterContext context, ResourceLocation sourceId) {
      var machine = context.machine();
      if (!needsOmittedCopies(machine)) {
        return;
      }
      var id = context.importedId(sourceId, "");
      if (!neededByCopyAll.contains(machine) && !explicitlySelected.getOrDefault(machine, Set.of()).contains(id)) {
        return;
      }
      var omitted = omittedMachineSources.computeIfAbsent(machine, key -> new LinkedHashSet<>());
      if (!omitted.add(id)) {
        return;
      }
      // Multi-level copy-all can multiply even a single skipped declaration.
      // Bound retained derived IDs independently of actual generated recipes.
      retainedOmittedCopies++;
      if (retainedOmittedCopies > MachineRecipeReloadListener.MAX_FILES) {
        throw new IllegalArgumentException(
            machine + ": Too many retained omitted machine recipe copies; at most " + MachineRecipeReloadListener.MAX_FILES);
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
      if (!settings.allRecipes() && selected.isEmpty()) {
        return;
      }
      var context = new RecipeAdapterContext(machine.id(), settings, registries, selected, tags);
      var builders = adapter.get().adapt(context);
      if (builders == null) {
        throw new IllegalArgumentException(machine.id() + ": Recipe adapter returned a null declaration list");
      }
      var additions = new RecipeAdapterCustomizations(settings.customization());
      for (var builder : builders) {
        var id = builder.id();
        var original = builder.build(registries);
        var declaration = additions.apply(original, original.machineId(), original.priority(), original.duration(), 0);
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
        if (source != null) {
          selected.add(source);
        } else if (!omittedIds.contains(sourceId)) {
          throw new IllegalArgumentException(machine + ": Missing recipe adapter source " + sourceId);
        }
      }
      return selected;
    }

    private void addGenerated(ResourceLocation id, MachineRecipeDeclaration declaration, ResourceLocation machine) {
      if (!id.getPath().startsWith("mmcece/adapted/") || originalsById.containsKey(id) || recipes.containsKey(id)) {
        throw new IllegalArgumentException(
            id + ": Recipe adapter ID is outside its directory or collides with an existing recipe");
      }
      if (recipes.size() >= MachineRecipeReloadListener.MAX_FILES) {
        throw new IllegalArgumentException("Too many generated machine recipes");
      }
      var source = MachineRecipeCompiler.capture(declaration, registries);
      var recipe = MachineRecipeCompiler.compile(source);
      if (!recipe.machineId().equals(machine)) {
        throw new IllegalArgumentException(id + ": Recipe adapter changed the target machine");
      }
      recipes.put(id, recipe);
      declarations.put(id, source);
      machineSources.get(machine).put(id, source);
    }
  }
}
