package com.lw.modularmachine.api.assembly;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.world.level.block.state.BlockState;

/** Immutable block candidate and its declared property constraints. */
public record AssemblyCandidate(BlockState state, List<String> requiredProperties) {
  public AssemblyCandidate {
    Objects.requireNonNull(state, "state");
    requiredProperties = List.copyOf(requiredProperties);
    if (requiredProperties.size() > 64 || Set.copyOf(requiredProperties).size() != requiredProperties.size()) {
      throw new IllegalArgumentException("Invalid assembly candidate constraints");
    }
    for (String name : requiredProperties) {
      if (name.isBlank() || state.getBlock().getStateDefinition().getProperty(name) == null) {
        throw new IllegalArgumentException("Unknown assembly candidate property: " + name);
      }
    }
  }

  /** Checks the supplied state without reading a world or granting permission. */
  public boolean matches(BlockState actual) {
    Objects.requireNonNull(actual, "actual");
    if (state.getBlock() != actual.getBlock()) {
      return false;
    }
    for (String name : requiredProperties) {
      var property = state.getBlock().getStateDefinition().getProperty(name);
      if (!state.getValue(property).equals(actual.getValue(property))) {
        return false;
      }
    }

    return true;
  }
}
