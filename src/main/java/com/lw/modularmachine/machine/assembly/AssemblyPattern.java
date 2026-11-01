package com.lw.modularmachine.machine.assembly;

import com.lw.modularmachine.machine.orientation.MachineOrientation;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.registry.MachineRegistries;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
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
    var parts = new ArrayList<Part>();
    Rotation rotation = switch (orientation.front()) {
      case EAST -> Rotation.CLOCKWISE_90;
      case SOUTH -> Rotation.CLOCKWISE_180;
      case WEST -> Rotation.COUNTERCLOCKWISE_90;
      default -> Rotation.NONE;
    };
    for (int index = 0; index < definition.parts().size(); index++) {
      var rule = rules.get(index);
      var states = new ArrayList<BlockState>();
      var alternatives = new ArrayList<MachineDefinition.Alternative>();
      if (rule.port() != null) {
        var port = rule.port();
        MachineRegistries.PORTS.values().stream().map(holder -> holder.get()).filter(block -> block.kind() == port)
            .map(Block::defaultBlockState).forEach(states::add);
      } else {
        for (var alternative : rule.alternatives()) {
          var state = alternative.block().defaultBlockState();
          for (var property : alternative.properties().entrySet()) {
            state = property(state, property.getKey(), property.getValue());
          }
          var properties = alternative.properties();
          if (definition.schemaVersion() == 1) {
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
    parts.sort(
        Comparator.comparingInt((Part part) -> part.position().getY()).thenComparingInt(part -> part.position().getX())
            .thenComparingInt(part -> part.position().getZ()));
    return new AssemblyPattern(definition.id(), epoch, origin, orientation, parts);
  }
  private static <T extends Comparable<T>> BlockState property(BlockState state, Property<T> property, Comparable<?> value) {
    return state.setValue(property, property.getValueClass().cast(value));
  }
}
