package com.lw.modularmachine.machine.assembly;

import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.structure.AsyncStructureWork;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal main-thread coordinator for detached rules and budgeted platform
 * candidate capture.
 */
@ApiStatus.Internal
final class AssemblyPreparation {
  private static final int MAX_CACHE_ENTRIES = 8;
  private static final int MAX_CACHE_PARTS = 1_048_576;
  private static final int MAX_CAPTURE_WORK = 8192;
  private static final LinkedHashMap<Key, AssemblyPattern> TEMPLATES = new LinkedHashMap<>(8, .75F, true);
  private static long cacheEpoch = -1;
  private static int cacheParts;

  private record Key(
      MachineDefinition definition,
      MachineOrientation orientation,
      Map<String, Integer> lengths,
      List<String> selectedModifierGroups) {
    @Override
    public int hashCode() {
      int result = System.identityHashCode(definition);
      result = 31 * result + orientation.hashCode();
      result = 31 * result + lengths.hashCode();

      return 31 * result + selectedModifierGroups.hashCode();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && key.definition == definition
          && key.orientation == orientation
          && key.lengths.equals(lengths)
          && key.selectedModifierGroups.equals(selectedModifierGroups);
    }
  }

  private record Compiled(MachineDefinition definition, List<MachineDefinition.Rule> rules) {
  }

  private final Thread owner = Thread.currentThread();
  private final MachineDefinition definition;
  private final long epoch;
  private final BlockPos origin;
  private final MachineOrientation orientation;
  private final Key key;
  private CompletableFuture<Compiled> rules;
  private AssemblyPattern.Preparation candidates;
  private CompletableFuture<AssemblyPattern> templateTask;
  private AssemblyPattern template;
  private CompletableFuture<AssemblyPattern> output;
  private boolean cancelled;

  AssemblyPreparation(MachineDefinition definition, long epoch, BlockPos origin, MachineOrientation orientation, Map<String, Integer> lengths) {
    this(definition, epoch, origin, orientation, lengths, List.of());
  }

  AssemblyPreparation(MachineDefinition definition, long epoch, BlockPos origin, MachineOrientation orientation, Map<String, Integer> lengths, List<String> selectedModifierGroups) {
    this.definition = definition;
    this.epoch = epoch;
    this.origin = origin.immutable();
    this.orientation = orientation;
    key = new Key(
        definition,
        orientation,
        Map.copyOf(lengths),
        ProjectionRules.normalizeSelection(definition, selectedModifierGroups));
    if (cacheEpoch != epoch) {
      clearCache();
      cacheEpoch = epoch;
    }
    template = TEMPLATES.get(key);
  }

  AssemblyPattern advance(long deadline) {
    requireOwner();
    if (cancelled) {
      throw new CancellationException("Assembly preparation cancelled");
    }
    if (System.nanoTime() >= deadline) {
      return null;
    }
    if (template == null) {
      if (templateTask == null) {
        if (rules == null) {
          var capturedDefinition = definition;
          var capturedOrientation = orientation;
          var capturedLengths = key.lengths();
          var capturedGroups = key.selectedModifierGroups();
          rules = AsyncStructureWork.submit(() -> {
            var resolved = capturedDefinition.resolveDynamic(capturedLengths);
            return new Compiled(resolved, ProjectionRules.compile(resolved, capturedOrientation, capturedGroups));
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
              compiled.rules(),
              key.selectedModifierGroups());
        }
        int work = 0;
        while (!candidates.complete() && work < MAX_CAPTURE_WORK && System.nanoTime() < deadline) {
          work += candidates.advance(Math.min(32, MAX_CAPTURE_WORK - work));
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
      if (cacheEpoch == epoch && template.parts().size() <= MAX_CACHE_PARTS) {
        var previous = TEMPLATES.put(key, template);
        cacheParts += template.parts().size() - (previous == null ? 0 : previous.parts().size());
        while (TEMPLATES.size() > MAX_CACHE_ENTRIES || cacheParts > MAX_CACHE_PARTS) {
          cacheParts -= TEMPLATES.remove(TEMPLATES.keySet().iterator().next()).parts().size();
        }
      }
      candidates = null;
      rules = null;
      templateTask = null;
    }
    if (output == null) {
      var captured = template;
      var position = origin;
      output = AsyncStructureWork.submit(() -> captured.relocate(position));
    }
    return output != null && output.isDone() ? output.join() : null;
  }

  void cancel() {
    requireOwner();
    cancelled = true;
    if (rules != null) {
      rules.cancel(false);
    }
    if (templateTask != null) {
      templateTask.cancel(false);
    }
    if (output != null) {
      output.cancel(false);
    }
    candidates = null;
    template = null;
  }

  private void requireOwner() {
    if (Thread.currentThread() != owner) {
      throw new IllegalStateException("Assembly candidate capture outside its owning server thread");
    }
  }

  static void clearCache() {
    TEMPLATES.clear();
    cacheParts = 0;
    cacheEpoch = -1;
  }
}
