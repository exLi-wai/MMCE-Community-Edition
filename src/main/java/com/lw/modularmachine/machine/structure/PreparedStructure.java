package com.lw.modularmachine.machine.structure;

import com.lw.modularmachine.block.ParallelControllerBlock;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.ApiStatus;

/**
 * Pure metadata built alongside the layout; transferred to the main thread
 * once.
 */
@ApiStatus.Internal
final class PreparedStructure {
  final CompiledStructure pattern;
  final IncrementalVerifier verifier;
  final StructureModifierTracker modifiers;
  final int[] interfaceParts, parallelParts;
  final Map<BlockPos, Integer> interfaceIndices;

  PreparedStructure(CompiledStructure pattern, long started) {
    this.pattern = pattern;
    verifier = new IncrementalVerifier(pattern.groups(), started);
    modifiers = new StructureModifierTracker(pattern, started);
    interfaceParts = IntStream.range(0, pattern.definition.parts().size())
        .filter(index -> !pattern.definition.parts().get(index).interfaceType().isEmpty()).toArray();
    var indices = new HashMap<BlockPos, Integer>();
    for (int index : interfaceParts) {
      indices.put(pattern.position(index), index);
    }
    interfaceIndices = Map.copyOf(indices);
    parallelParts = IntStream.range(0, pattern.definition.parts().size())
        .filter(
            index -> pattern.definition.parts().get(index).rule().alternatives().stream()
                .anyMatch(alternative -> alternative.block() instanceof ParallelControllerBlock))
        .toArray();
  }
}
