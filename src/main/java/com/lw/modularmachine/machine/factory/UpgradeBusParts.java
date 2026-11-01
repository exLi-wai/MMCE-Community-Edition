package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.block.UpgradeBusBlock;
import com.lw.modularmachine.machine.structure.StructureService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;

/**
 * Compiles candidate positions once per oriented structure, without inspecting
 * live inventories.
 */
public record UpgradeBusParts(List<BlockPos> positions, Set<BlockPos> membership) {
  public UpgradeBusParts {
    positions = positions.stream().map(BlockPos::immutable).toList();
    membership = Set.copyOf(membership);
  }

  public static UpgradeBusParts compile(StructureService.Registration registration) {
    var positions = new ArrayList<BlockPos>();
    for (int index = 0; index < registration.pattern.definition.parts().size(); index++) {
      var rule = registration.pattern.definition.parts().get(index).rule();
      if (rule.alternatives().stream().anyMatch(alternative -> alternative.block() instanceof UpgradeBusBlock)) {
        positions.add(registration.pattern.position(index).immutable());
      }
    }

    return new UpgradeBusParts(positions, Set.copyOf(positions));
  }
}
