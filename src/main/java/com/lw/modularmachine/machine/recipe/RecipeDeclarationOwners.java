package com.lw.modularmachine.machine.recipe;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/**
 * Machine ownership of the declarations from one source in a prospective
 * reload.
 */
final class RecipeDeclarationOwners {
  private final Set<ResourceLocation> skipped;
  private final Map<ResourceLocation, ResourceLocation> machines;

  RecipeDeclarationOwners(Set<ResourceLocation> skipped, Map<ResourceLocation, ResourceLocation> machines) {
    this.skipped = new LinkedHashSet<>(skipped);
    this.machines = new LinkedHashMap<>(machines);
  }

  void accept(ResourceLocation id, ResourceLocation machine) {
    machines.put(id, machine);
  }

  void skip(ResourceLocation id, ResourceLocation machine) {
    skipped.add(id);
    if (machine != null) {
      machines.put(id, machine);
    }
  }

  Set<ResourceLocation> skipped() {
    return skipped;
  }

  Map<ResourceLocation, ResourceLocation> resolve(Set<ResourceLocation> availableMachines, Map<ResourceLocation, ResourceLocation> previous, Map<ResourceLocation, Set<ResourceLocation>> omissions) {
    for (var id : skipped) {
      var machine = machines.get(id);
      if (machine == null || !availableMachines.contains(machine)) {
        // Only an unreadable declaration still present in this same source
        // can inherit its last published owner. A removed file leaves no entry.
        machine = previous.get(id);
      }
      if (machine == null || !availableMachines.contains(machine)) {
        machines.remove(id);
        continue;
      }
      machines.put(id, machine);
      omissions.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(machine);
    }

    return Map.copyOf(machines);
  }
}
