package com.lw.modularmachine.client.preview;

import com.lw.modularmachine.network.ProjectionPayloads;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;

/**
 * Immutable world-space projection partitioned once, independent from the JEI
 * category.
 */
public record PreviewRenderScene(long request, ResourceLocation dimension, List<Section> sections) {
  public record Section(long key, List<ProjectionPayloads.Entry> entries, AABB bounds) {
    public Section {
      entries = List.copyOf(entries);
    }
  }
  public PreviewRenderScene {
    sections = List.copyOf(sections);
  }
  public static PreviewRenderScene create(ProjectionPayloads.Snapshot header, List<ProjectionPayloads.Entry> entries) {
    return create(header.request(), header.dimension(), entries);
  }
  public static PreviewRenderScene create(long request, ResourceLocation dimension, List<ProjectionPayloads.Entry> entries) {
    var grouped = new LinkedHashMap<Long, ArrayList<ProjectionPayloads.Entry>>();
    for (var entry : entries) {
      grouped.computeIfAbsent(SectionPos.asLong(entry.position()), ignored -> new ArrayList<>()).add(entry);
    }
    var sections = new ArrayList<Section>();
    for (var entry : grouped.entrySet()) {
      var pos = SectionPos.of(entry.getKey()).origin();
      for (int start = 0; start < entry.getValue().size(); start += 64) {
        sections.add(
            new Section(
                sections.size(),
                entry.getValue().subList(start, Math.min(start + 64, entry.getValue().size())),
                new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 16, pos.getY() + 16, pos.getZ() + 16)));
      }
    }
    return new PreviewRenderScene(request, dimension, sections);
  }
}
