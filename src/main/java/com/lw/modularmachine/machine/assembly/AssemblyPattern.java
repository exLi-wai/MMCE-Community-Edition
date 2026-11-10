package com.lw.modularmachine.machine.assembly;

import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.orientation.PropertySpace;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.block.MachinePortBlock;
import com.lw.modularmachine.util.minecraft.state.BlockStateValues;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.concurrent.CancellationException;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.neoforge.registries.DeferredBlock;

/**
 * Server-compiled display/build candidates share the exact definition
 * orientation rules.
 */
public record AssemblyPattern(
    ResourceLocation machine,
    long epoch,
    BlockPos origin,
    MachineOrientation orientation,
    MachineDefinition sourceDefinition,
    Map<String, Integer> dynamicLengths,
    List<String> selectedModifierGroups,
    List<Part> parts) {
  public AssemblyPattern(ResourceLocation machine, long epoch, BlockPos origin, MachineOrientation orientation, MachineDefinition sourceDefinition, Map<String, Integer> dynamicLengths, List<Part> parts) {
    this(machine, epoch, origin, orientation, sourceDefinition, dynamicLengths, List.of(), parts);
  }

  public record Part(BlockPos position, MachineDefinition.Rule rule, List<BlockState> states) {
    public Part {
      position = position.immutable();
      states = List.copyOf(states);
    }
    public boolean matches(BlockState state) {
      return states.stream().anyMatch(candidate -> candidate.getBlock() == state.getBlock()) && rule.matches(state);
    }
  }
  public AssemblyPattern {
    origin = origin.immutable();
    dynamicLengths = Map.copyOf(dynamicLengths);
    selectedModifierGroups = List.copyOf(selectedModifierGroups);
    parts = List.copyOf(parts);
  }

  public static AssemblyPattern compile(MachineDefinition definition, long epoch, BlockPos origin, MachineOrientation orientation) {
    return compile(definition, epoch, origin, orientation, definition.defaultDynamicLengths(-1));
  }

  public static AssemblyPattern compile(MachineDefinition definition, long epoch, BlockPos origin, MachineOrientation orientation, Map<String, Integer> lengths) {
    return compile(definition, epoch, origin, orientation, lengths, List.of());
  }

  public static AssemblyPattern compile(MachineDefinition definition, long epoch, BlockPos origin, MachineOrientation orientation, Map<String, Integer> lengths, List<String> selectedModifierGroups) {
    definition = definition.resolveDynamic(lengths);
    if (!definition.allows(orientation)) {
      throw new IllegalArgumentException("Unsupported assembly orientation");
    }
    var selected = ProjectionRules.normalizeSelection(definition, selectedModifierGroups);
    var rules = ProjectionRules.compile(definition, orientation, selected);
    var preparation = new Preparation(definition, epoch, origin, orientation, rules, selected);
    while (!preparation.complete()) {
      preparation.advance(512);
    }
    return preparation.finish();
  }

  /**
   * Main-thread candidate callbacks are resumable; supplied rules are immutable.
   */
  public static final class Preparation {
    private record CapturedPart(
        BlockPos position,
        MachineDefinition.Rule source,
        List<BlockState> states,
        List<MachineDefinition.Alternative> alternatives) {
      Part freeze() {
        return new Part(
            position,
            source.port() == null ? new MachineDefinition.Rule(null, alternatives) : source,
            states);
      }
    }

    private final MachineDefinition definition;
    private final long epoch;
    private final BlockPos origin;
    private final MachineOrientation orientation;
    private final List<MachineDefinition.Rule> rules;
    private final List<String> selectedModifierGroups;
    private final ArrayList<CapturedPart> parts = new ArrayList<>();
    private final Map<PortKind, List<BlockState>> ports = new EnumMap<>(PortKind.class);
    private final Rotation rotation;
    private int cursor;
    private int alternativeCursor;
    private MachineDefinition.Rule activeRule;
    private List<BlockState> activeStates;
    private List<MachineDefinition.Alternative> activeAlternatives;
    private Iterator<DeferredBlock<MachinePortBlock>> portScan;
    private ArrayList<BlockState> portStates;
    private boolean finished;

    public Preparation(MachineDefinition definition, long epoch, BlockPos origin, MachineOrientation orientation, List<MachineDefinition.Rule> rules) {
      this(definition, epoch, origin, orientation, rules, List.of());
    }

    public Preparation(MachineDefinition definition, long epoch, BlockPos origin, MachineOrientation orientation, List<MachineDefinition.Rule> rules, List<String> selectedModifierGroups) {
      if (!definition.allows(orientation) || rules.size() != definition.parts().size()) {
        throw new IllegalArgumentException("Invalid projection rule snapshot");
      }
      this.definition = definition;
      this.epoch = epoch;
      this.origin = origin.immutable();
      this.orientation = orientation;
      this.rules = rules;
      this.selectedModifierGroups = List.copyOf(selectedModifierGroups);
      rotation = switch (orientation.front()) {
        case EAST -> Rotation.CLOCKWISE_90;
        case SOUTH -> Rotation.CLOCKWISE_180;
        case WEST -> Rotation.COUNTERCLOCKWISE_90;
        default -> Rotation.NONE;
      };
    }

    public boolean complete() {
      return cursor == definition.parts().size();
    }

    public int advance(int budget) {
      if (budget < 0) {
        throw new IllegalArgumentException("Negative preparation budget");
      }
      if (finished) {
        throw new IllegalStateException("Candidates already transferred");
      }
      int used = 0;
      while (!complete() && used < budget) {
        if (activeRule == null) {
          activeRule = rules.get(cursor);
          alternativeCursor = 0;
          if (activeRule.port() == null) {
            activeStates = new ArrayList<>();
            activeAlternatives = new ArrayList<>();
          } else if (!ports.containsKey(activeRule.port())) {
            portScan = MachineRegistries.PORTS.values().iterator();
            portStates = new ArrayList<>();
          }
          used++;
          continue;
        }
        if (portScan != null) {
          if (portScan.hasNext()) {
            var block = portScan.next().get();
            if (block.kind() == activeRule.port()) {
              portStates.add(block.defaultBlockState());
            }
          } else {
            // Freeze once per port kind so repeated hatches share the same candidate list.
            ports.put(activeRule.port(), List.copyOf(portStates));
            portStates = null;
            portScan = null;
          }
          used++;
          continue;
        }
        if (activeRule.port() == null && alternativeCursor < activeRule.alternatives().size()) {
          var alternative = activeRule.alternatives().get(alternativeCursor++);
          var state = BlockStateValues.apply(alternative.block().defaultBlockState(), alternative.properties());
          var properties = alternative.properties();
          if (selectedModifierGroups.isEmpty()
              && definition.parts().get(cursor).propertiesSpace() == PropertySpace.LEGACY) {
            state = state.rotate(rotation);
            var transformed = new HashMap<Property<?>, Comparable<?>>();
            for (var key : properties.keySet()) {
              transformed.put(key, state.getValue(key));
            }
            properties = transformed;
          }
          activeStates.add(state);
          activeAlternatives.add(new MachineDefinition.Alternative(alternative.snapshot(), properties));
          used++;
          continue;
        }
        var states = activeRule.port() == null ? activeStates : ports.get(activeRule.port());
        parts.add(
            new CapturedPart(
                origin.offset(orientation.toWorldOffset(definition.parts().get(cursor).offset())),
                activeRule,
                states,
                activeAlternatives));
        activeRule = null;
        activeStates = null;
        activeAlternatives = null;
        cursor++;
        used++;
      }
      return used;
    }

    /** Ownership transfer: sorting may run on a worker, no more advance calls. */
    public AssemblyPattern finish() {
      if (!complete() || finished) {
        throw new IllegalStateException("Incomplete or transferred candidates");
      }
      finished = true;
      var frozen = new ArrayList<Part>(parts.size());
      for (var part : parts) {
        if (Thread.currentThread().isInterrupted()) {
          throw new CancellationException();
        }
        frozen.add(part.freeze());
      }
      frozen.sort(
          Comparator.comparingInt((Part part) -> part.position().getY())
              .thenComparingInt(part -> part.position().getX()).thenComparingInt(part -> part.position().getZ()));
      return new AssemblyPattern(
          definition.id(),
          epoch,
          origin,
          orientation,
          definition.sourceDefinition(),
          definition.dynamicLengths(),
          selectedModifierGroups,
          frozen);
    }
  }

  /** Pure translation of an already sorted origin template. */
  public AssemblyPattern relocate(BlockPos target) {
    if (target.equals(origin)) {
      return this;
    }
    var relocated = new ArrayList<Part>(parts.size());
    for (var part : parts) {
      if (Thread.currentThread().isInterrupted()) {
        throw new CancellationException();
      }
      var offset = part.position().subtract(origin);
      relocated.add(
          new Part(
              new BlockPos(
                  Math.addExact(target.getX(), offset.getX()),
                  Math.addExact(target.getY(), offset.getY()),
                  Math.addExact(target.getZ(), offset.getZ())),
              part.rule(),
              part.states()));
    }
    return new AssemblyPattern(
        machine,
        epoch,
        target,
        orientation,
        sourceDefinition,
        dynamicLengths,
        selectedModifierGroups,
        relocated);
  }
}
