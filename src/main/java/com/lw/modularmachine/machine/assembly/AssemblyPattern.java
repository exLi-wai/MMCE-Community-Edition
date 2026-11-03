package com.lw.modularmachine.machine.assembly;

import com.lw.modularmachine.machine.orientation.MachineOrientation;
import com.lw.modularmachine.machine.orientation.PropertySpace;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.registry.MachineRegistries;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.EnumMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Server-compiled display/build candidates share the exact definition
 * orientation rules.
 */
public record AssemblyPattern(
    ResourceLocation machine,
    long epoch,
    BlockPos origin,
    MachineOrientation orientation,
    List<Part> parts) {
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
    parts = List.copyOf(parts);
  }

  public static AssemblyPattern compile(MachineDefinition definition, long epoch, BlockPos origin, MachineOrientation orientation) {
    if (!definition.allows(orientation)) {
      throw new IllegalArgumentException("Unsupported assembly orientation");
    }
    var rules = definition.rulesFor(orientation);
    var preparation = new Preparation(definition, epoch, origin, orientation, rules);
    while (!preparation.complete()) {
      preparation.advance(512);
    }
    return preparation.finish();
  }

  /**
   * Main-thread candidate callbacks are resumable; supplied rules are immutable.
   */
  public static final class Preparation {
    private final MachineDefinition definition;
    private final long epoch;
    private final BlockPos origin;
    private final MachineOrientation orientation;
    private final List<MachineDefinition.Rule> rules;
    private final ArrayList<Part> parts = new ArrayList<>();
    private final Map<PortKind, List<BlockState>> ports = new EnumMap<>(PortKind.class);
    private final Rotation rotation;
    private int cursor;
    private boolean finished;

    public Preparation(MachineDefinition definition, long epoch, BlockPos origin, MachineOrientation orientation, List<MachineDefinition.Rule> rules) {
      if (!definition.allows(orientation) || rules.size() != definition.parts().size()) {
        throw new IllegalArgumentException("Invalid projection rule snapshot");
      }
      this.definition = definition;
      this.epoch = epoch;
      this.origin = origin.immutable();
      this.orientation = orientation;
      this.rules = rules;
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
        int index = cursor++;
        used++;
        var rule = rules.get(index);
        var states = new ArrayList<BlockState>();
        var alternatives = new ArrayList<MachineDefinition.Alternative>();
        if (rule.port() != null) {
          var port = rule.port();
          states.addAll(
              ports.computeIfAbsent(
                  port,
                  kind -> MachineRegistries.PORTS.values().stream().map(holder -> holder.get())
                      .filter(block -> block.kind() == kind).map(Block::defaultBlockState).toList()));
        } else {
          for (var alternative : rule.alternatives()) {
            var state = alternative.block().defaultBlockState();
            for (var property : alternative.properties().entrySet()) {
              state = property(state, property.getKey(), property.getValue());
            }
            var properties = alternative.properties();
            if (definition.parts().get(index).propertiesSpace() == PropertySpace.LEGACY) {
              state = state.rotate(rotation);
              var transformed = new HashMap<Property<?>, Comparable<?>>();
              for (var key : properties.keySet()) {
                transformed.put(key, state.getValue(key));
              }
              properties = transformed;
            }
            states.add(state);
            alternatives.add(new MachineDefinition.Alternative(alternative.snapshot(), properties));
          }
          rule = new MachineDefinition.Rule(null, alternatives);
        }
        parts.add(
            new Part(origin.offset(orientation.toWorldOffset(definition.parts().get(index).offset())), rule, states));
      }
      return used;
    }

    /** Ownership transfer: sorting may run on a worker, no more advance calls. */
    public AssemblyPattern finish() {
      if (!complete() || finished) {
        throw new IllegalStateException("Incomplete or transferred candidates");
      }
      finished = true;
      parts.sort(
          Comparator.comparingInt((Part part) -> part.position().getY())
              .thenComparingInt(part -> part.position().getX()).thenComparingInt(part -> part.position().getZ()));
      return new AssemblyPattern(definition.id(), epoch, origin, orientation, parts);
    }
  }

  /** Pure translation of an already sorted origin template. */
  public AssemblyPattern relocate(BlockPos target) {
    var relocated = new ArrayList<Part>(parts.size());
    for (var part : parts) {
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
    return new AssemblyPattern(machine, epoch, target, orientation, relocated);
  }
  private static <T extends Comparable<T>> BlockState property(BlockState state, Property<T> property, Comparable<?> value) {
    return state.setValue(property, property.getValueClass().cast(value));
  }
}
