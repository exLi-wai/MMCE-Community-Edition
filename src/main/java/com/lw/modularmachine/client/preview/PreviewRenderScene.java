package com.lw.modularmachine.client.preview;

import com.lw.modularmachine.network.ProjectionPayloads;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;

/**
 * Immutable scene with spatial indexes shared by projection and blueprint
 * previews.
 */
public final class PreviewRenderScene {
  private static final int BATCH_SIZE = 512;
  private final long request;
  private final ResourceLocation dimension;
  private final List<Section> sections;
  private final Map<Long, List<Section>> spatial;
  private final Map<BlockPos, ProjectionPayloads.Entry> entries;
  private final List<ProjectionPayloads.Entry> animated;
  private final Map<BlockPos, Integer> indices;

  public record Section(
      long key,
      long spatialKey,
      List<ProjectionPayloads.Entry> entries,
      AABB bounds,
      boolean animated) {
    public Section {
      entries = List.copyOf(entries);
    }
  }

  private PreviewRenderScene(long request, ResourceLocation dimension, List<Section> sections, Map<Long, List<Section>> spatial, Map<BlockPos, ProjectionPayloads.Entry> entries, List<ProjectionPayloads.Entry> animated) {
    this.request = request;
    this.dimension = dimension;
    this.sections = List.copyOf(sections);
    this.spatial = Map.copyOf(spatial);
    this.entries = Map.copyOf(entries);
    this.animated = List.copyOf(animated);
    var indices = new LinkedHashMap<BlockPos, Integer>();
    int index = 0;
    // Preserve caller order for blueprint hit selection.
    for (var position : entries.keySet()) {
      indices.put(position, index++);
    }
    this.indices = Map.copyOf(indices);
  }

  public long request() {
    return request;
  }
  public ResourceLocation dimension() {
    return dimension;
  }
  public List<Section> sections() {
    return sections;
  }
  public List<Section> atSection(long key) {
    return spatial.getOrDefault(key, List.of());
  }
  public ProjectionPayloads.Entry at(BlockPos position) {
    return entries.get(position);
  }
  public List<ProjectionPayloads.Entry> animated() {
    return animated;
  }
  public int indexAt(BlockPos position) {
    return indices.getOrDefault(position, -1);
  }

  public static PreviewRenderScene create(ProjectionPayloads.Snapshot header, List<ProjectionPayloads.Entry> entries) {
    return create(header.request(), header.dimension(), entries);
  }

  public static PreviewRenderScene create(long request, ResourceLocation dimension, List<ProjectionPayloads.Entry> entries) {
    var grouped = new LinkedHashMap<Long, ArrayList<ProjectionPayloads.Entry>>();
    var positions = new LinkedHashMap<BlockPos, ProjectionPayloads.Entry>();
    var animated = new ArrayList<ProjectionPayloads.Entry>();
    for (var entry : entries) {
      grouped.computeIfAbsent(SectionPos.asLong(entry.position()), ignored -> new ArrayList<>()).add(entry);
      positions.put(entry.position(), entry);
      if (entry.states().size() > 1) {
        animated.add(entry);
      }
    }
    var sections = new ArrayList<Section>();
    var spatial = new LinkedHashMap<Long, List<Section>>();
    for (var entry : grouped.entrySet()) {
      var batches = new ArrayList<Section>();
      for (int start = 0; start < entry.getValue().size(); start += BATCH_SIZE) {
        var batch = entry.getValue().subList(start, Math.min(start + BATCH_SIZE, entry.getValue().size()));
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (var part : batch) {
          var pos = part.position();
          minX = Math.min(minX, pos.getX());
          minY = Math.min(minY, pos.getY());
          minZ = Math.min(minZ, pos.getZ());
          maxX = Math.max(maxX, pos.getX());
          maxY = Math.max(maxY, pos.getY());
          maxZ = Math.max(maxZ, pos.getZ());
        }
        var section = new Section(
            sections.size(),
            entry.getKey(),
            batch,
            new AABB(minX, minY, minZ, maxX + 1D, maxY + 1D, maxZ + 1D),
            batch.stream().anyMatch(part -> part.states().size() > 1));
        sections.add(section);
        batches.add(section);
      }
      spatial.put(entry.getKey(), List.copyOf(batches));
    }
    return new PreviewRenderScene(request, dimension, sections, spatial, positions, animated);
  }
}
