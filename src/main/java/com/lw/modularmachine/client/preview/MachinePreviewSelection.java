package com.lw.modularmachine.client.preview;

import com.lw.modularmachine.api.client.preview.PreviewSceneData;
import com.lw.modularmachine.network.MachineJeiPayloads.Part;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal client-only display choices. Inclusion affects the bill of
 * materials, not geometry.
 */
@ApiStatus.Internal
public final class MachinePreviewSelection {
  private final MachinePreviewDefinition definition;
  private final int color;
  private final int[] lengths;
  private final boolean[] included;
  private List<Part> parts = List.of();
  private List<Map<Item, Integer>> groupCounts = List.of();
  private Map<Item, Integer> materials = Map.of();
  private PreviewSceneData scene;
  private float extent = 1;
  private Build pending;
  private boolean failed;

  private static final class Build {
    final MachinePreviewDefinition.Layout layout;
    final List<PreviewSceneData.Entry> entries = new ArrayList<>();
    final HashSet<BlockPos> occupied = new HashSet<>();
    final List<Map<Item, Integer>> counts = new ArrayList<>();
    int cursor;

    Build(MachinePreviewDefinition.Layout layout, int groups) {
      this.layout = layout;
      for (int i = 0; i < groups; i++) {
        counts.add(new LinkedHashMap<>());
      }
    }
  }

  public MachinePreviewSelection(MachinePreviewDefinition definition, int color) {
    this(definition, color, definition.segments().stream().mapToInt(segment -> segment.minimum()).toArray());
  }

  /** Seeds all desired lengths before creating the first incremental layout. */
  public MachinePreviewSelection(MachinePreviewDefinition definition, int color, int[] initialLengths) {
    this.definition = definition;
    this.color = color;
    if (initialLengths.length != definition.segments().size()) {
      throw new IllegalArgumentException("Missing initial display lengths");
    }
    lengths = initialLengths.clone();
    included = new boolean[lengths.length + 1];
    Arrays.fill(included, true);
    try {
      pending = new Build(definition.layout(lengths), included.length);
    } catch (IllegalArgumentException | ArithmeticException invalid) {
      failed = true;
    }
  }

  public int length(int segment) {
    return lengths[segment];
  }

  public boolean setLength(int segment, int value) {
    var bounds = definition.segments().get(segment);
    if (value < bounds.minimum() || value > bounds.maximum()) {
      return false;
    }
    if (lengths[segment] == value && !failed) {
      return true;
    }
    var next = lengths.clone();
    next[segment] = value;
    try {
      var build = new Build(definition.layout(next), included.length);
      lengths[segment] = value;
      pending = build;
      failed = false;
      return true;
    } catch (IllegalArgumentException | ArithmeticException invalid) {
      return false;
    }
  }

  public boolean included(int group) {
    return included[group];
  }

  public void toggleIncluded(int group) {
    included[group] = !included[group];
    updateMaterials();
  }

  public boolean building() {
    return pending != null;
  }

  public boolean failed() {
    return failed;
  }

  /** Bounded work; a complete scene replaces the previous one atomically. */
  public int advance(int budget, long deadline) {
    if (pending == null) {
      return 0;
    }
    int used = 0;
    try {
      while (used < budget && pending.cursor < pending.layout.size() && System.nanoTime() < deadline) {
        int index = pending.cursor++;
        var part = pending.layout.get(index);
        if (!pending.occupied.add(part.offset())) {
          throw new IllegalArgumentException("Overlapping display parts");
        }
        pending.entries.add(new PreviewSceneData.Entry(part.offset(), part.alternatives(), List.of()));
        var item = part.alternatives().getFirst().getBlock().asItem();
        if (item != Items.AIR) {
          pending.counts.get(pending.layout.groupAt(index)).merge(item, 1, Integer::sum);
        }
        used++;
      }
      if (pending.cursor == pending.layout.size()) {
        var complete = new PreviewSceneData(color, pending.entries);
        parts = pending.layout;
        groupCounts = pending.counts;
        scene = complete;
        extent = scene.extent();
        pending = null;
        updateMaterials();
      }
    } catch (IllegalArgumentException | ArithmeticException invalid) {
      pending = null;
      failed = true;
    }
    return used;
  }

  private void updateMaterials() {
    var counts = new LinkedHashMap<Item, Integer>();
    for (int group = 0; group < groupCounts.size(); group++) {
      if (included[group]) {
        groupCounts.get(group).forEach((item, count) -> counts.merge(item, count, Integer::sum));
      }
    }
    materials = Collections.unmodifiableMap(counts);
  }

  public Map<Item, Integer> materials() {
    return materials;
  }

  public List<Part> parts() {
    return parts;
  }

  public PreviewSceneData scene() {
    return scene;
  }

  public float extent() {
    return extent;
  }
}
