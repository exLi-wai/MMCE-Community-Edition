package com.lw.modularmachine.machine.assembly;

import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.structure.AsyncStructureWork;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.network.ProjectionPayloads;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.ApiStatus;

/**
 * Projection-only packet adaptation of the shared budgeted assembly
 * preparation.
 */
@ApiStatus.Internal
public final class ProjectionPreparation {
  public record Ready(AssemblyPattern pattern, List<ProjectionPayloads.Entry> entries) {
  }
  private final AssemblyPreparation preparation;
  private AssemblyPattern pattern;
  private CompletableFuture<Ready> output;
  private boolean cancelled;

  ProjectionPreparation(MachineDefinition definition, long epoch, BlockPos origin, MachineOrientation orientation) {
    this(definition, epoch, origin, orientation, definition.defaultDynamicLengths(-1));
  }

  public ProjectionPreparation(MachineDefinition definition, long epoch, BlockPos origin, MachineOrientation orientation, Map<String, Integer> lengths) {
    this(definition, epoch, origin, orientation, lengths, List.of());
  }

  public ProjectionPreparation(MachineDefinition definition, long epoch, BlockPos origin, MachineOrientation orientation, Map<String, Integer> lengths, List<String> selectedModifierGroups) {
    preparation = new AssemblyPreparation(definition, epoch, origin, orientation, lengths, selectedModifierGroups);
  }

  public Ready advance(long deadline) {
    if (cancelled) {
      throw new CancellationException("Projection preparation cancelled");
    }
    if (pattern == null) {
      pattern = preparation.advance(deadline);
      if (pattern == null) {
        return null;
      }
    }
    if (output == null) {
      var captured = pattern;
      output = AsyncStructureWork.submit(() -> {
        if (captured.parts().stream().anyMatch(part -> part.states().isEmpty())) {
          throw new IllegalArgumentException("Selected projection has an unrepresentable part");
        }
        var entries = captured.parts().stream()
            .map(
                part -> new ProjectionPayloads.Entry(
                    part.position(),
                    part.states(),
                    part.rule().alternatives().isEmpty() ? List.of() : part.rule().alternatives().getFirst()
                        .properties().keySet().stream().map(property -> property.getName()).sorted().toList()))
            .toList();
        return new Ready(captured, entries);
      });
    }
    return output != null && output.isDone() ? output.join() : null;
  }

  static void clearCache() {
    AssemblyPreparation.clearCache();
  }

  public void cancel() {
    cancelled = true;
    preparation.cancel();
    pattern = null;
    if (output != null) {
      output.cancel(false);
    }
  }
}
