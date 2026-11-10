package com.lw.modularmachine.api.assembly;

import com.lw.modularmachine.api.orientation.MachineOrientation;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable projection metadata for integrations. This describes a chosen
 * layout, not a structure proof or a world-placement authorization.
 */
public record AssemblyPlan(
    long request,
    ResourceLocation dimension,
    BlockPos controller,
    ResourceLocation machine,
    long definitionEpoch,
    MachineOrientation orientation,
    Map<String, Integer> dynamicLengths,
    List<String> selectedModifierGroups,
    int totalParts) {
  public AssemblyPlan {
    Objects.requireNonNull(dimension, "dimension");
    controller = Objects.requireNonNull(controller, "controller").immutable();
    Objects.requireNonNull(machine, "machine");
    Objects.requireNonNull(orientation, "orientation");
    dynamicLengths = Map.copyOf(dynamicLengths);
    selectedModifierGroups = List.copyOf(selectedModifierGroups);
    if (request < 0
        || definitionEpoch < 0
        || totalParts < 0
        || Set.copyOf(selectedModifierGroups).size() != selectedModifierGroups.size()) {
      throw new IllegalArgumentException("Invalid assembly plan");
    }
    for (var entry : dynamicLengths.entrySet()) {
      if (entry.getKey().isBlank() || entry.getValue() < 0) {
        throw new IllegalArgumentException("Invalid assembly dynamic length");
      }
    }
    for (String group : selectedModifierGroups) {
      if (group.isBlank()) {
        throw new IllegalArgumentException("Invalid assembly modifier group");
      }
    }
  }
}
