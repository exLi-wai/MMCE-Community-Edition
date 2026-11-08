package com.lw.modularmachine.machine.selection;

import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.assembly.ProjectionPreparation;
import com.lw.modularmachine.network.ProjectionPayloads;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal adapter to the same bounded candidate preparation used by
 * blueprints.
 */
@ApiStatus.Internal
public final class RangedSelectionPreview {
  private final ProjectionPreparation preparation;

  public RangedSelectionPreview(DynamicDefinitionEditor.Result result, int selected, int length, long epoch) {
    var definition = result.definition();
    var lengths = new LinkedHashMap<>(definition.defaultDynamicLengths(-1));
    String id = result.segmentIds().get(Math.clamp(selected, 0, result.segmentIds().size() - 1));
    if (!id.isEmpty()) {
      var segment = definition.dynamicPatterns().stream().filter(pattern -> pattern.id().equals(id)).findFirst()
          .orElseThrow();
      if (length < segment.minimum() || length > segment.maximum()) {
        throw new IllegalArgumentException("预览长度超出所选动态段范围");
      }
      lengths.put(segment.id(), length);
    }
    preparation = new ProjectionPreparation(definition, epoch, BlockPos.ZERO, MachineOrientation.IDENTITY, lengths);
  }

  public List<ProjectionPayloads.Entry> advance(long deadline) {
    var ready = preparation.advance(deadline);
    return ready == null ? null : ready.entries();
  }

  public void cancel() {
    preparation.cancel();
  }
}
