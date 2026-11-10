package com.lw.modularmachine.api.assembly;

import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;

/** Immutable world position and the acceptable candidates in a projection. */
public record AssemblyPart(BlockPos position, List<AssemblyCandidate> candidates) {
  public AssemblyPart {
    position = Objects.requireNonNull(position, "position").immutable();
    candidates = List.copyOf(candidates);
  }
}
