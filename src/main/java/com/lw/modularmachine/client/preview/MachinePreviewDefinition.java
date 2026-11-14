package com.lw.modularmachine.client.preview;

import com.lw.modularmachine.machine.structure.DynamicPatternDefinition;
import com.lw.modularmachine.network.MachineJeiPayloads;
import com.lw.modularmachine.network.MachineJeiPayloads.Part;
import com.lw.modularmachine.network.MachineJeiPayloads.Segment;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;
import org.jetbrains.annotations.ApiStatus;
import net.minecraft.core.BlockPos;

/**
 * Internal immutable display templates. They confer no world or production
 * authority.
 */
@ApiStatus.Internal
public final class MachinePreviewDefinition {
  private final List<Part> declarations;
  private final List<Segment> segments;
  private final int fixed;
  private final int[] templateStarts;
  private final Layout minimum;

  public MachinePreviewDefinition(List<Part> declarations, int fixed, List<Segment> segments) {
    this(declarations, fixed, segments, false);
  }

  /**
   * Read-only world captures may contain only a dynamic group or no solid blocks.
   */
  public static MachinePreviewDefinition captured(List<Part> main, List<Part> dynamic) {
    var parts = new ArrayList<Part>(main.size() + dynamic.size());
    parts.addAll(main);
    parts.addAll(dynamic);
    var segments = dynamic.isEmpty() ? List.<Segment>of() : List
        .of(new Segment("selection", 1, 1, BlockPos.ZERO, new BlockPos(0, 0, 1), 0, dynamic.size(), 0));

    return new MachinePreviewDefinition(parts, main.size(), segments, true);
  }

  /**
   * Captured drafts may have no controller/fixed parts and multiple templates.
   */
  public static MachinePreviewDefinition captured(List<Part> declarations, int fixed, List<Segment> segments) {
    return new MachinePreviewDefinition(declarations, fixed, segments, true);
  }

  private MachinePreviewDefinition(List<Part> declarations, int fixed, List<Segment> segments, boolean captured) {
    this.declarations = List.copyOf(declarations);
    this.segments = List.copyOf(segments);
    this.fixed = fixed;
    if (fixed < (captured ? 0 : 1) || fixed > declarations.size() || segments.size() > 64) {
      throw new IllegalArgumentException("Invalid display template size");
    }
    templateStarts = new int[segments.size()];
    int cursor = fixed;
    var ids = new HashSet<String>();
    for (int i = 0; i < segments.size(); i++) {
      if (!ids.add(segments.get(i).id())) {
        throw new IllegalArgumentException("Duplicate display segment");
      }
      templateStarts[i] = cursor;
      cursor = Math.addExact(cursor, segments.get(i).templates());
    }
    if (cursor != declarations.size()) {
      throw new IllegalArgumentException("Incomplete display templates");
    }
    minimum = layout(segments.stream().mapToInt(Segment::minimum).toArray());
  }

  public List<Segment> segments() {
    return segments;
  }

  public Layout minimum() {
    return minimum;
  }

  public Layout layout(int[] lengths) {
    return new Layout(lengths);
  }

  /**
   * A lazy list keeps maximum lengths out of the catalog and expands only the
   * viewed scene.
   */
  public final class Layout extends AbstractList<Part> implements RandomAccess {
    private final int[] lengths;
    private final int[] ends;

    private Layout(int[] lengths) {
      if (lengths.length != segments.size()) {
        throw new IllegalArgumentException("Missing display lengths");
      }
      this.lengths = lengths.clone();
      ends = new int[segments.size() + 1];
      ends[0] = fixed;
      long size = fixed;
      for (int i = 0; i < segments.size(); i++) {
        var segment = segments.get(i);
        int length = lengths[i];
        if (length < segment.minimum() || length > segment.maximum()) {
          throw new IllegalArgumentException("Display length outside bounds");
        }
        size += segment.head() + (long) segment.body() * length + segment.tail();
        if (size > MachineJeiPayloads.MAX_PARTS) {
          throw new IllegalArgumentException("Expanded display exceeds its size limit");
        }
        ends[i + 1] = (int) size;
      }
    }

    public int groupAt(int index) {
      Objects.checkIndex(index, size());
      int low = 0, high = ends.length - 1;
      while (low < high) {
        int middle = (low + high) >>> 1;
        if (index < ends[middle]) {
          high = middle;
        } else {
          low = middle + 1;
        }
      }
      return low;
    }

    @Override
    public int size() {
      return ends[ends.length - 1];
    }

    @Override
    public Part get(int index) {
      int group = groupAt(index);
      if (group == 0) {
        return declarations.get(index);
      }
      int segmentIndex = group - 1;
      var segment = segments.get(segmentIndex);
      int local = index - ends[group - 1];
      int repetition = 0;
      int template = templateStarts[segmentIndex];
      if (local < segment.head()) {
        template += local;
      } else {
        local -= segment.head();
        int bodyParts = segment.body() * lengths[segmentIndex];
        if (local < bodyParts) {
          repetition = local / segment.body();
          template += segment.head() + local % segment.body();
        } else {
          repetition = lengths[segmentIndex];
          template += segment.head() + segment.body() + local - bodyParts;
        }
      }
      var part = declarations.get(template);
      return new Part(
          DynamicPatternDefinition.translated(part.offset(), segment.origin(), segment.step(), repetition),
          part.alternatives());
    }
  }
}
