package com.lw.modularmachine.api.client.preview;

import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Immutable structure input independent of network payloads and recipe viewers.
 */
public record PreviewSceneData(int machineColor, List<Entry> entries) {
  public PreviewSceneData {
    machineColor &= 0xFFFFFF;
    entries = List.copyOf(entries);
  }

  public record Entry(BlockPos position, List<BlockState> states, List<String> properties) {
    public Entry {
      position = Objects.requireNonNull(position, "position").immutable();
      states = List.copyOf(states);
      properties = List.copyOf(properties);
      if (states.size() > 256 || properties.size() > 64) {
        throw new IllegalArgumentException("Too many preview candidates or constraints");
      }
    }

    /** Tests declared constraints without consulting a live world. */
    public boolean matches(BlockState actual) {
      Objects.requireNonNull(actual, "actual");
      for (var state : states) {
        if (state.getBlock() != actual.getBlock()) {
          continue;
        }
        boolean matches = true;
        for (String name : properties) {
          var property = state.getBlock().getStateDefinition().getProperty(name);
          if (property == null || !state.getValue(property).equals(actual.getValue(property))) {
            matches = false;
            break;
          }
        }
        if (matches) {
          return true;
        }
      }
      return false;
    }
  }

  /** Block extents, with a unit cube for an empty scene. */
  public AABB bounds() {
    if (entries.isEmpty()) {
      return new AABB(0, 0, 0, 1, 1, 1);
    }
    int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
    int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
    for (var entry : entries) {
      var position = entry.position();
      minX = Math.min(minX, position.getX());
      minY = Math.min(minY, position.getY());
      minZ = Math.min(minZ, position.getZ());
      maxX = Math.max(maxX, position.getX());
      maxY = Math.max(maxY, position.getY());
      maxZ = Math.max(maxZ, position.getZ());
    }

    return new AABB(minX, minY, minZ, maxX + 1D, maxY + 1D, maxZ + 1D);
  }

  public float extent() {
    var bounds = bounds();

    return (float) Math.max(bounds.getXsize(), Math.max(bounds.getYsize(), bounds.getZsize()));
  }
}
