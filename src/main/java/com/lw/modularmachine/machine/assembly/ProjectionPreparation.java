package com.lw.modularmachine.machine.assembly;

import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.structure.AsyncStructureWork;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.network.ProjectionPayloads;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.ApiStatus;

/**
 * Main-thread candidate capture, pure worker rule compilation/sort/packet
 * preparation.
 */
@ApiStatus.Internal
public final class ProjectionPreparation {
  public record Ready(AssemblyPattern pattern, List<ProjectionPayloads.Entry> entries) {
  }
  private record Key(MachineDefinition definition, MachineOrientation orientation, Map<String, Integer> lengths) {
    @Override
    public int hashCode() {
      return 31 * (31 * System.identityHashCode(definition) + orientation.hashCode()) + lengths.hashCode();
    }
    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && key.definition == definition
          && key.orientation == orientation
          && key.lengths.equals(lengths);
    }
  }
  private static final LinkedHashMap<Key, AssemblyPattern> TEMPLATES = new LinkedHashMap<>(8, .75F, true);
  private static long cacheEpoch = -1;
  private final MachineDefinition definition;
  private final long epoch;
  private final BlockPos origin;
  private final MachineOrientation orientation;
  private final Key key;
  private record Compiled(MachineDefinition definition, List<MachineDefinition.Rule> rules) {
  }
  private CompletableFuture<Compiled> rules;
  private AssemblyPattern.Preparation candidates;
  private CompletableFuture<AssemblyPattern> templateTask;
  private AssemblyPattern template;
  private CompletableFuture<Ready> output;

  ProjectionPreparation(MachineDefinition definition, long epoch, BlockPos origin, MachineOrientation orientation) {
    this(definition, epoch, origin, orientation, definition.defaultDynamicLengths(-1));
  }

  public ProjectionPreparation(MachineDefinition definition, long epoch, BlockPos origin, MachineOrientation orientation, Map<String, Integer> lengths) {
    this.definition = definition;
    this.epoch = epoch;
    this.origin = origin.immutable();
    this.orientation = orientation;
    key = new Key(definition, orientation, Map.copyOf(lengths));
    if (cacheEpoch != epoch) {
      TEMPLATES.clear();
      cacheEpoch = epoch;
    }
    template = TEMPLATES.get(key);
  }

  public Ready advance(long deadline) {
    if (template == null) {
      if (templateTask == null) {
        if (rules == null) {
          var capturedDefinition = definition;
          var capturedOrientation = orientation;
          var capturedLengths = key.lengths();
          rules = AsyncStructureWork.submit(() -> {
            var resolved = capturedDefinition.resolveDynamic(capturedLengths);
            return new Compiled(resolved, resolved.rulesFor(capturedOrientation));
          });
        }
        if (rules == null || !rules.isDone()) {
          return null;
        }
        if (candidates == null) {
          var compiled = rules.join();
          candidates = new AssemblyPattern.Preparation(
              compiled.definition(),
              epoch,
              BlockPos.ZERO,
              orientation,
              compiled.rules());
        }
        int work = 0;
        while (!candidates.complete() && work < 8192 && System.nanoTime() < deadline) {
          work += candidates.advance(32);
        }
        if (!candidates.complete()) {
          return null;
        }
        var captured = candidates;
        templateTask = AsyncStructureWork.submit(captured::finish);
        return null;
      }
      if (!templateTask.isDone()) {
        return null;
      }
      template = templateTask.join();
      if (cacheEpoch == epoch) {
        TEMPLATES.put(key, template);
        while (TEMPLATES.size() > 8) {
          TEMPLATES.remove(TEMPLATES.keySet().iterator().next());
        }
      }
      candidates = null;
      rules = null;
      templateTask = null;
    }
    if (output == null) {
      var captured = template;
      var position = origin;
      output = AsyncStructureWork.submit(() -> {
        var pattern = captured.relocate(position);
        var entries = pattern.parts().stream()
            .map(
                part -> new ProjectionPayloads.Entry(
                    part.position(),
                    part.states(),
                    part.rule().alternatives().isEmpty() ? List.of() : part.rule().alternatives().getFirst()
                        .properties().keySet().stream().map(property -> property.getName()).sorted().toList()))
            .toList();
        return new Ready(pattern, entries);
      });
    }
    return output != null && output.isDone() ? output.join() : null;
  }

  static void clearCache() {
    TEMPLATES.clear();
    cacheEpoch = -1;
  }

  public void cancel() {
    if (rules != null) {
      rules.cancel(false);
    }
    if (templateTask != null) {
      templateTask.cancel(false);
    }
    if (output != null) {
      output.cancel(false);
    }
  }
}
