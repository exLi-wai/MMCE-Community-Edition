package com.lw.modularmachine.machine.structure;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal main-thread dependency index; each bit identifies one of at most 64
 * segments.
 */
@ApiStatus.Internal
final class DynamicStructureWatches {
  private static final class Segment {
    final Map<BlockPos, Boolean> positions = new LinkedHashMap<>();
    final Map<Long, Integer> chunks = new HashMap<>();
  }

  private final Segment[] segments = new Segment[64];
  private final Map<BlockPos, Long> positions = new HashMap<>();
  private final Map<BlockPos, Long> contents = new HashMap<>();
  private final Map<Long, Integer> sections = new HashMap<>();
  private final Map<Long, Long> chunks = new HashMap<>();
  private long cleaning;

  Set<Long> sections() {
    return sections.keySet();
  }

  Set<Long> chunks() {
    return chunks.keySet();
  }

  long affected(BlockPos position, boolean contentChange) {
    return (contentChange ? contents : positions).getOrDefault(position, 0L);
  }

  long affectedChunk(long chunk) {
    return chunks.getOrDefault(chunk, 0L);
  }

  void add(int index, BlockPos position, boolean watchesContents) {
    var segment = segments[index];
    if (segment == null) {
      segment = segments[index] = new Segment();
    }
    var pos = position.immutable();
    long bit = 1L << index;
    Boolean previous = segment.positions.putIfAbsent(pos, watchesContents);
    if (previous == null) {
      long section = SectionPos.asLong(pos);
      long chunk = chunk(section);
      if (!positions.containsKey(pos)) {
        sections.merge(section, 1, Integer::sum);
      }
      positions.merge(pos, bit, (left, right) -> left | right);
      segment.chunks.merge(chunk, 1, Integer::sum);
      chunks.merge(chunk, bit, (left, right) -> left | right);
    }
    if (watchesContents) {
      segment.positions.put(pos, true);
      contents.merge(pos, bit, (left, right) -> left | right);
    }
  }

  void invalidate(long affected) {
    cleaning |= affected;
  }

  boolean ready() {
    return cleaning == 0;
  }

  /**
   * Retire old probes before restarting a segment; clean segments stay
   * subscribed.
   */
  int advanceCleanup(int budget, LongConsumer removeSection, LongConsumer removeChunk) {
    int used = 0;
    while (used < budget && cleaning != 0) {
      int index = Long.numberOfTrailingZeros(cleaning);
      long bit = 1L << index;
      var segment = segments[index];
      if (segment == null || segment.positions.isEmpty()) {
        segments[index] = null;
        cleaning &= ~bit;
        used++;
        continue;
      }
      var iterator = segment.positions.entrySet().iterator();
      while (used < budget && iterator.hasNext()) {
        var entry = iterator.next();
        var pos = entry.getKey();
        long section = SectionPos.asLong(pos);
        long chunk = chunk(section);
        if (removeBit(positions, pos, bit)) {
          int remaining = sections.get(section) - 1;
          if (remaining == 0) {
            sections.remove(section);
            removeSection.accept(section);
          } else {
            sections.put(section, remaining);
          }
        }
        if (entry.getValue()) {
          removeBit(contents, pos, bit);
        }
        int remaining = segment.chunks.get(chunk) - 1;
        if (remaining == 0) {
          segment.chunks.remove(chunk);
          if (removeBit(chunks, chunk, bit)) {
            removeChunk.accept(chunk);
          }
        } else {
          segment.chunks.put(chunk, remaining);
        }
        iterator.remove();
        used++;
      }
    }
    return used;
  }

  private static <T> boolean removeBit(Map<T, Long> index, T key, long bit) {
    long remaining = index.getOrDefault(key, 0L) & ~bit;
    if (remaining == 0) {
      index.remove(key);
      return true;
    }
    index.put(key, remaining);
    return false;
  }

  private static long chunk(long section) {
    return ChunkPos.asLong(SectionPos.x(section), SectionPos.z(section));
  }

  void clear() {
    Arrays.fill(segments, null);
    positions.clear();
    contents.clear();
    sections.clear();
    chunks.clear();
    cleaning = 0;
  }
}
