package com.lw.modularmachine.machine.controller;

import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.orientation.MachineOrientation;
import com.lw.modularmachine.machine.structure.CompiledStructure;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Budgeted selection hints. Only StructureService can publish a production
 * proof.
 */
public final class GenericControllerDiscovery {
  private static MinecraftServer budgetServer;
  private static int tick, globalWork;
  private long epoch = -1, retryAfter;
  private MachineOrientation orientation;
  private ResourceLocation blueprint, dedicated;
  private boolean factory;
  private List<MachineDefinition> candidates = List.of();
  private CompiledStructure.Builder builder;
  private CompiledStructure pattern;
  private CompiledStructure.Discovery probe;
  private int candidateIndex;

  public ResourceLocation advance(ServerLevel level, BlockPos origin, MachineOrientation pose, ResourceLocation target, ResourceLocation fixed, boolean factoryController) {
    ServerThreadGuard.require(level, "Controller discovery");
    if (epoch != StructureService.definitionEpoch()
        || orientation != pose
        || !Objects.equals(blueprint, target)
        || !Objects.equals(dedicated, fixed)
        || factory != factoryController) {
      clear();
      epoch = StructureService.definitionEpoch();
      orientation = pose;
      blueprint = target;
      dedicated = fixed;
      factory = factoryController;
      var ordered = new LinkedHashMap<ResourceLocation, MachineDefinition>();
      var selected = target == null ? null : StructureService.definition(target);
      if (selected != null) {
        ordered.put(target, selected);
      }
      if (fixed != null) {
        var definition = StructureService.definition(fixed);
        if (definition != null && (!definition.requiresBlueprint() || fixed.equals(target))) {
          ordered.put(fixed, definition);
        }
      } else {
        StructureService.definitionsSnapshot().values().stream().filter(this::eligible)
            .sorted(Comparator.comparing(MachineDefinition::id))
            .forEach(definition -> ordered.putIfAbsent(definition.id(), definition));
      }
      candidates = List.copyOf(ordered.values());
    }
    if (level.getGameTime() < retryAfter || candidates.isEmpty()) {
      return null;
    }
    if (budgetServer != level.getServer() || tick != level.getServer().getTickCount()) {
      budgetServer = level.getServer();
      tick = budgetServer.getTickCount();
      globalWork = 128;
    }
    int work = Math.min(16, globalWork);
    while (work > 0 && candidateIndex < candidates.size()) {
      if (builder == null) {
        if (!candidates.get(candidateIndex).allows(pose)) {
          candidateIndex++;
          work--;
          globalWork--;
          continue;
        }
        builder = CompiledStructure.prepare(candidates.get(candidateIndex), origin, pose);
      }
      if (!builder.complete()) {
        int used = builder.advance(work);
        work -= used;
        globalWork -= used;
        if (!builder.complete() || work == 0) {
          return null;
        }
      }
      if (pattern == null) {
        pattern = builder.result();
        probe = pattern.discover(level.getGameTime());
      }
      int used = probe.advance(level, work, level.getGameTime(), MachineConfig.ROUND_TIMEOUT_TICKS.get());
      work -= used;
      globalWork -= used;
      if (probe.complete()) {
        var found = probe.matched() ? pattern.definition.id() : null;
        next();
        if (found != null) {
          return found;
        }
      } else {
        return null;
      }
    }
    if (candidateIndex >= candidates.size()) {
      candidateIndex = 0;
      retryAfter = level.getGameTime() + 20;
    }
    return null;
  }

  private boolean eligible(MachineDefinition definition) {
    return definition != null && !definition.requiresBlueprint() && (factory || !definition.factoryOnly());
  }

  private void next() {
    candidateIndex++;
    probe = null;
    builder = null;
    pattern = null;
  }

  public void clear() {
    epoch = -1;
    retryAfter = 0;
    orientation = null;
    blueprint = null;
    dedicated = null;
    candidates = List.of();
    candidateIndex = 0;
    probe = null;
    builder = null;
    pattern = null;
  }
}
