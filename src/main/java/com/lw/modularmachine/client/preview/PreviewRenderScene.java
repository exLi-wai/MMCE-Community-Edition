package com.lw.modularmachine.client.preview;

import com.lw.modularmachine.network.ProjectionPayloads;
import com.lw.modularmachine.machine.controller.DedicatedControllerCatalog;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntMaps;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
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
  private final int machineColor;
  private final List<Section> sections;
  private final Map<Long, List<Section>> spatial;
  private final Long2ObjectMap<ProjectionPayloads.Entry> entries;
  private final List<ProjectionPayloads.Entry> animated;
  private final Long2IntMap indices;

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

  private PreviewRenderScene(long request, ResourceLocation dimension, int machineColor, List<Section> sections, Map<Long, List<Section>> spatial, Long2ObjectMap<ProjectionPayloads.Entry> entries, Long2IntMap indices, List<ProjectionPayloads.Entry> animated) {
    this.request = request;
    this.dimension = dimension;
    this.machineColor = machineColor & 0xFFFFFF;
    this.sections = List.copyOf(sections);
    this.spatial = Map.copyOf(spatial);
    // These maps are built privately and never mutated after scene publication.
    // Mixed packed keys avoid BlockPos hash collisions on large planar structures.
    this.entries = Long2ObjectMaps.unmodifiable(entries);
    this.indices = Long2IntMaps.unmodifiable(indices);
    this.animated = List.copyOf(animated);
  }

  public long request() {
    return request;
  }
  public ResourceLocation dimension() {
    return dimension;
  }
  public int machineColor() {
    return machineColor;
  }
  public List<Section> sections() {
    return sections;
  }
  public List<Section> atSection(long key) {
    return spatial.getOrDefault(key, List.of());
  }
  public ProjectionPayloads.Entry at(BlockPos position) {
    return entries.get(position.asLong());
  }
  public List<ProjectionPayloads.Entry> animated() {
    return animated;
  }
  public int indexAt(BlockPos position) {
    return indices.get(position.asLong());
  }

  public static PreviewRenderScene create(ProjectionPayloads.Snapshot header, List<ProjectionPayloads.Entry> entries) {
    return create(header.request(), header.dimension(), header.machine(), entries);
  }

  public static PreviewRenderScene create(long request, ResourceLocation dimension, List<ProjectionPayloads.Entry> entries) {
    return create(request, dimension, 0xFFFFFF, entries);
  }

  public static PreviewRenderScene create(long request, ResourceLocation dimension, ResourceLocation machine, List<ProjectionPayloads.Entry> entries) {
    return create(request, dimension, DedicatedControllerCatalog.color(machine), entries);
  }

  private static PreviewRenderScene create(long request, ResourceLocation dimension, int machineColor, List<ProjectionPayloads.Entry> entries) {
    var grouped = new LinkedHashMap<Long, ArrayList<ProjectionPayloads.Entry>>();
    var positions = new Long2ObjectOpenHashMap<ProjectionPayloads.Entry>(entries.size());
    var indices = new Long2IntOpenHashMap(entries.size());
    indices.defaultReturnValue(-1);
    var animated = new ArrayList<ProjectionPayloads.Entry>();
    for (var entry : entries) {
      grouped.computeIfAbsent(SectionPos.asLong(entry.position()), ignored -> new ArrayList<>()).add(entry);
      long position = entry.position().asLong();
      // Preserve first occurrence order for blueprint hit selection, even on
      // duplicates.
      if (!indices.containsKey(position)) {
        indices.put(position, indices.size());
      }
      positions.put(position, entry);
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
    return new PreviewRenderScene(request, dimension, machineColor, sections, spatial, positions, indices, animated);
  }
}
