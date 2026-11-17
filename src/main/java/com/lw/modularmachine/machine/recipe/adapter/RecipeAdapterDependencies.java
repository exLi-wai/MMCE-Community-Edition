package com.lw.modularmachine.machine.recipe.adapter;

import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterImport;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/** Internal deterministic source-first order for machine recipe copies. */
@ApiStatus.Internal
final class RecipeAdapterDependencies {
  private RecipeAdapterDependencies() {
  }

  static List<ResourceLocation> order(Map<ResourceLocation, List<RecipeAdapterImport>> imports) {
    var remaining = new HashMap<ResourceLocation, Integer>();
    var dependents = new HashMap<ResourceLocation, List<ResourceLocation>>();
    var ready = new PriorityQueue<ResourceLocation>();
    for (var entry : imports.entrySet()) {
      var dependencies = machineDependencies(entry.getKey(), entry.getValue(), imports.keySet());
      remaining.put(entry.getKey(), dependencies.size());
      if (dependencies.isEmpty()) {
        ready.add(entry.getKey());
      }
      for (var dependency : dependencies) {
        dependents.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(entry.getKey());
      }
    }

    var result = new ArrayList<ResourceLocation>(imports.size());
    while (!ready.isEmpty()) {
      var machine = ready.remove();
      result.add(machine);
      for (var dependent : dependents.getOrDefault(machine, List.of())) {
        int count = remaining.compute(dependent, (ignored, value) -> value - 1);
        if (count == 0) {
          ready.add(dependent);
        }
      }
    }
    if (result.size() != imports.size()) {
      throw new IllegalArgumentException(
          "Cyclic machine recipe adapter dependencies: " + remaining.entrySet().stream()
              .filter(entry -> entry.getValue() > 0).map(Map.Entry::getKey).sorted().toList());
    }
    return List.copyOf(result);
  }

  private static Set<ResourceLocation> machineDependencies(ResourceLocation machine, List<RecipeAdapterImport> settings, Set<ResourceLocation> machines) {
    var dependencies = new HashSet<ResourceLocation>();
    for (var entry : settings) {
      if (RecipeAdapterRegistry.known(entry.adapter())) {
        continue;
      }
      if (!machines.contains(entry.adapter())) {
        throw new IllegalArgumentException(machine + ": Unknown recipe adapter or source machine " + entry.adapter());
      }
      dependencies.add(entry.adapter());
    }
    return dependencies;
  }
}
