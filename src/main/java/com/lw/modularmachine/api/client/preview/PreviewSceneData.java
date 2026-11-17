package com.lw.modularmachine.api.client.preview;

import com.lw.modularmachine.util.minecraft.state.BlockStateValues;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import java.util.Collections;
import net.minecraft.nbt.CompoundTag;

/**
 * Immutable structure input independent of network payloads and recipe viewers.
 */
public record PreviewSceneData(int machineColor, List<Entry> entries) {
  public PreviewSceneData {
    machineColor &= 0xFFFFFF;
    entries = List.copyOf(entries);
  }

  /**
   * Candidate counts have no per-position cap; producers retain their overall
   * resource budgets.
   */
  public record Entry(
      BlockPos position,
      List<BlockState> states,
      List<String> properties,
      List<PreviewBlockData> previewTags) {
    public Entry(BlockPos position, List<BlockState> states, List<String> properties) {
      this(position, states, properties, Collections.nCopies(states.size(), PreviewBlockData.EMPTY));
    }

    public Entry(BlockPos position, List<BlockState> states, List<String> properties, CompoundTag previewData) {
      this(position, states, properties, Collections.nCopies(states.size(), PreviewBlockData.of(previewData)));
    }

    public Entry {
      position = Objects.requireNonNull(position, "position").immutable();
      states = List.copyOf(states);
      properties = List.copyOf(properties);
      previewTags = List.copyOf(previewTags);
      if (properties.size() > 64
          || previewTags.size() != states.size()
          || previewTags.stream().mapToLong(PreviewBlockData::sizeInBytes).sum() > 4_194_304L) {
        throw new IllegalArgumentException("Too many preview constraints");
      }
    }

    public PreviewBlockData previewData(int step) {
      return previewTags.isEmpty() ? PreviewBlockData.EMPTY : previewTags.get(Math.floorMod(step, previewTags.size()));
    }

    /** Tests declared constraints without consulting a live world. */
    public boolean matches(BlockState actual) {
      Objects.requireNonNull(actual, "actual");
      return BlockStateValues.matchesAny(states, properties, actual);
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
