package com.lw.modularmachine.machine.controller;

import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.structure.AsyncStructureWork;
import com.lw.modularmachine.machine.structure.CompiledStructure;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.machine.structure.StructureSnapshot;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Selection hints only. Production always needs a current StructureService
 * proof.
 */
public final class GenericControllerDiscovery {
  private static MinecraftServer budgetServer;
  private static int tick;
  private static long deadline;
  private long epoch = -1, revision = -1;
  private MachineOrientation orientation;
  private ResourceLocation blueprint, dedicated;
  private boolean factory;
  private List<MachineDefinition> candidates = List.of();
  private CompletableFuture<CompiledStructure> layout;
  private CompiledStructure pattern;
  private StructureSnapshot.Capture capture;
  private AsyncStructureWork.Permit permit;
  private CompletableFuture<StructureSnapshot.Result> matching;
  private int candidateIndex;

  public ResourceLocation advance(ServerLevel level, BlockPos origin, MachineOrientation pose, ResourceLocation target, ResourceLocation fixed, boolean factoryController) {
    ServerThreadGuard.require(level, "Controller discovery");
    var selected = target == null ? null : StructureService.definition(target);
    if (selected != null && selected.allows(pose)) {
      clear();
      return target;
    }
    if (fixed != null) {
      clear();
      var definition = StructureService.definition(fixed);
      return definition != null
          && definition.allows(pose)
          && (!definition.requiresBlueprint() || fixed.equals(target)) ? fixed : null;
    }
    long currentRevision = StructureService.worldRevision(level);
    if (epoch != StructureService.definitionEpoch()
        || revision != currentRevision
        || orientation != pose
        || !Objects.equals(blueprint, target)
        || !Objects.equals(dedicated, fixed)
        || factory != factoryController) {
      clear();
      epoch = StructureService.definitionEpoch();
      revision = currentRevision;
      orientation = pose;
      blueprint = target;
      dedicated = fixed;
      factory = factoryController;
      candidates = StructureService.definitionsSnapshot().values().stream()
          .filter(definition -> !definition.requiresBlueprint() && (factory || !definition.factoryOnly()))
          .sorted(Comparator.comparing(MachineDefinition::id)).toList();
    }
    if (candidateIndex >= candidates.size()) {
      return null;
    }
    if (budgetServer != level.getServer() || tick != level.getServer().getTickCount()) {
      budgetServer = level.getServer();
      tick = budgetServer.getTickCount();
      deadline = System.nanoTime() + 1_000_000L;
    }
    try {
      if (matching != null) {
        if (!matching.isDone()) {
          return null;
        }
        boolean matched = matching.join().matched();
        var id = pattern.definition.id();
        next();
        return matched ? id : null;
      }
      var definition = candidates.get(candidateIndex);
      if (!definition.allows(pose)) {
        next();
        return null;
      }
      if (layout == null) {
        layout = AsyncStructureWork.prepare(definition, origin, pose);
      }
      if (layout == null || !layout.isDone()) {
        return null;
      }
      if (pattern == null) {
        pattern = layout.join();
      }
      if (capture == null) {
        permit = AsyncStructureWork.acquireSnapshot();
        if (permit == null) {
          return null;
        }
        capture = new StructureSnapshot.Capture(pattern, IntStream.range(0, definition.parts().size()).toArray());
      }
      while (!capture.complete() && System.nanoTime() < deadline) {
        capture.advance(level, 256);
      }
      if (capture.complete()) {
        var detached = capture.finish();
        matching = AsyncStructureWork.submit(detached::match);
        if (matching != null) {
          var ownedPermit = permit;
          matching.whenComplete((result, failure) -> ownedPermit.close());
          permit = null;
          capture = null;
        }
      }
    } catch (RuntimeException invalid) {
      next();
    }
    return null;
  }

  private void next() {
    candidateIndex++;
    if (permit != null) {
      permit.close();
    }
    permit = null;
    matching = null;
    capture = null;
    layout = null;
    pattern = null;
  }

  public void clear() {
    next();
    candidateIndex = 0;
    epoch = -1;
    revision = -1;
    orientation = null;
    blueprint = null;
    dedicated = null;
    candidates = List.of();
  }
}
