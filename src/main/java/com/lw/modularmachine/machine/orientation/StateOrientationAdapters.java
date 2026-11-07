package com.lw.modularmachine.machine.orientation;

import com.google.gson.JsonParseException;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

/**
 * Captures only requested registry/property data on the server executor, before
 * pure compilation.
 */
public final class StateOrientationAdapters {
  private static final Logger LOGGER = LogUtils.getLogger();
  private StateOrientationAdapters() {
  }

  public enum TransformKind {
    DIRECTION,
    AXIS,
    SCALAR,
    UNSUPPORTED
  }

  public record PropertyReference(String name, String literal, String path) {
  }

  public record BlockReference(ResourceLocation id, String path, List<PropertyReference> properties) {
    public BlockReference {
      properties = List.copyOf(properties);
    }
  }

  public record PropertySnapshot(
      Property<?> property,
      Map<String, Comparable<?>> literals,
      Set<Comparable<?>> possibleValues,
      TransformKind kind) {
    public PropertySnapshot {
      literals = Map.copyOf(literals);
      possibleValues = Set.copyOf(possibleValues);
    }

    public Comparable<?> value(String literal, String path) {
      Comparable<?> result = literals.get(literal);
      if (result == null) {
        throw new JsonParseException(path + ": Invalid or uncaptured property value: " + literal);
      }
      return result;
    }
  }

  public record BlockSnapshot(ResourceLocation id, Block block, Map<String, PropertySnapshot> properties) {
    public BlockSnapshot {
      properties = Map.copyOf(properties);
    }

    public PropertySnapshot property(String name, String path) {
      PropertySnapshot result = properties.get(name);
      if (result == null) {
        throw new JsonParseException(path + ": Unknown or uncaptured block property: " + name);
      }
      return result;
    }
  }

  public record RegistrySnapshot(Map<ResourceLocation, BlockSnapshot> blocks) {
    public RegistrySnapshot {
      blocks = Map.copyOf(blocks);
    }

    public boolean has(ResourceLocation id) {
      return blocks.containsKey(id);
    }

    public BlockSnapshot block(ResourceLocation id, String path) {
      BlockSnapshot result = blocks.get(id);
      if (result == null) {
        throw new JsonParseException(path + ": Unknown or uncaptured block: " + id);
      }
      return result;
    }
  }

  public static RegistrySnapshot capture(List<BlockReference> references) {
    var server = ServerLifecycleHooks.getCurrentServer();
    if (server != null && !server.isSameThread()) {
      throw new IllegalStateException("Registry snapshot capture outside server thread");
    }
    Map<ResourceLocation, List<BlockReference>> grouped = new LinkedHashMap<>();
    for (BlockReference reference : references) {
      grouped.computeIfAbsent(reference.id(), ignored -> new ArrayList<>()).add(reference);
    }
    Map<ResourceLocation, BlockSnapshot> blocks = new LinkedHashMap<>();
    for (var entry : grouped.entrySet()) {
      ResourceLocation id = entry.getKey();
      if (!BuiltInRegistries.BLOCK.containsKey(id)) {
        // A definition may come from another pack (or a removed mod); skip the
        // block instead of failing the whole reload.
        LOGGER.warn(
            "Machine definition references unknown block {} ({}); skipping that block",
            id,
            entry.getValue().getFirst().path());
        continue;
      }
      try {
        Block block = BuiltInRegistries.BLOCK.get(id);
        Map<Property<?>, TransformKind> classifications = classifyProperties(block);
        Map<String, Map<String, Comparable<?>>> literals = new LinkedHashMap<>();
        Map<String, Property<?>> properties = new LinkedHashMap<>();
        for (BlockReference reference : entry.getValue()) {
          for (PropertyReference required : reference.properties()) {
            Property<?> property = block.getStateDefinition().getProperty(required.name());
            if (property == null) {
              throw new JsonParseException(required.path() + ": Unknown block property: " + required.name());
            }
            Comparable<?> parsed = property.getValue(required.literal()).orElseThrow(
                () -> new JsonParseException(required.path() + ": Invalid property value: " + required.literal()));
            properties.put(required.name(), property);
            literals.computeIfAbsent(required.name(), ignored -> new LinkedHashMap<>()).put(required.literal(), parsed);
          }
        }
        Map<String, PropertySnapshot> snapshots = new LinkedHashMap<>();
        properties.forEach((name, property) -> {
          TransformKind kind = classifications.getOrDefault(property, TransformKind.UNSUPPORTED);
          // Direction/axis adapters have at most six values. Opaque/scalar domains are
          // never
          // enumerated.
          Set<Comparable<?>> domain = kind == TransformKind.DIRECTION || kind == TransformKind.AXIS ? Set
              .copyOf(property.getPossibleValues()) : Set.copyOf(literals.get(name).values());
          snapshots.put(name, new PropertySnapshot(property, literals.get(name), domain, kind));
        });
        blocks.put(id, new BlockSnapshot(id, block, snapshots));
      } catch (JsonParseException | IllegalArgumentException invalid) {
        LOGGER.warn("Machine definition block {} is unusable ({}); skipping that block", id, invalid.getMessage());
      }
    }
    return new RegistrySnapshot(blocks);
  }

  /**
   * Direction and axis properties are transformed with the controller pose; every
   * other registered state property is an exact scalar constraint. This lets
   * stairs, slabs, doors and modded variant blocks retain all of their state
   * values without a per-block allowlist.
   */
  private static Map<Property<?>, TransformKind> classifyProperties(Block block) {
    Map<Property<?>, TransformKind> result = new HashMap<>();
    for (Property<?> property : block.getStateDefinition().getProperties()) {
      var values = property.getPossibleValues();
      if (!values.isEmpty() && values.stream().allMatch(Direction.class::isInstance)) {
        result.put(property, TransformKind.DIRECTION);
      } else if (!values.isEmpty() && values.stream().allMatch(Direction.Axis.class::isInstance)) {
        result.put(property, TransformKind.AXIS);
      } else {
        result.put(property, TransformKind.SCALAR);
      }
    }
    return result;
  }
}
