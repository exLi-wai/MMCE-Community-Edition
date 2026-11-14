package com.lw.modularmachine.client.selection;

import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.client.preview.MachinePreviewDefinition;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Box;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Design;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Segment;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal client-thread snapshots. Raw world states are independent of design
 * parameters; dirty-section reads and template compilation share one budget.
 */
@ApiStatus.Internal
public final class RangedSelectionCapture {
  private ClientLevel level;
  private long revision = -1;
  private long completedRevision = -2;
  private long generation;
  private Design design;
  private List<Bounds> geometry = List.of();
  private BlockPos origin = BlockPos.ZERO;
  private final Map<Long, Section> sections = new LinkedHashMap<>();
  private final Map<Long, List<Section>> chunks = new LinkedHashMap<>();
  private LinkedHashSet<Section> dirty = new LinkedHashSet<>();
  private Discovery discovery;
  private Batch batch;
  private RangedSelectionPreviewCompiler compiler;
  private boolean recompile, invalid;
  private MachinePreviewDefinition definition;
  private RangedSelectionExportSnapshot exportSnapshot;
  private Component error = Component.empty();

  private record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    static Bounds of(Box box) {
      var first = box.first();
      var second = box.second();

      return new Bounds(
          Math.min(first.getX(), second.getX()),
          Math.min(first.getY(), second.getY()),
          Math.min(first.getZ(), second.getZ()),
          Math.max(first.getX(), second.getX()),
          Math.max(first.getY(), second.getY()),
          Math.max(first.getZ(), second.getZ()));
    }

    Bounds section(int x, int y, int z) {
      return new Bounds(
          Math.max(minX, x << 4),
          Math.max(minY, y << 4),
          Math.max(minZ, z << 4),
          Math.min(maxX, (x << 4) + 15),
          Math.min(maxY, (y << 4) + 15),
          Math.min(maxZ, (z << 4) + 15));
    }

    Iterator<BlockPos> positions() {
      return BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ).iterator();
    }
  }

  private static final class Discovery {
    final List<Bounds> bounds;
    int region;
    int x, y, z;

    Discovery(List<Bounds> bounds) {
      this.bounds = bounds;
      beginRegion();
    }

    private void beginRegion() {
      if (!done()) {
        x = bounds.get(region).minX >> 4;
        y = bounds.get(region).minY >> 4;
        z = bounds.get(region).minZ >> 4;
      }
    }

    boolean done() {
      return region == bounds.size();
    }

    void next() {
      var range = bounds.get(region);
      if (++x <= range.maxX >> 4) {
        return;
      }
      x = range.minX >> 4;
      if (++z <= range.maxZ >> 4) {
        return;
      }
      z = range.minZ >> 4;
      if (++y <= range.maxY >> 4) {
        return;
      }
      region++;
      beginRegion();
    }
  }

  private static final class Section {
    final int x, y, z;
    final List<Bounds> ranges = new ArrayList<>();
    Long2ObjectOpenHashMap<BlockState> states;

    Section(int x, int y, int z) {
      this.x = x;
      this.y = y;
      this.z = z;
    }
  }

  /** Overlapping ranges are visited with duplicate work charged to the budget. */
  private static final class Cells {
    final Iterator<Bounds> ranges;
    final BitSet visited = new BitSet(4096);
    Iterator<BlockPos> positions = List.<BlockPos>of().iterator();
    boolean duplicate;

    Cells(Section section) {
      ranges = section.ranges.iterator();
    }

    BlockPos next() {
      while (!positions.hasNext()) {
        if (!ranges.hasNext()) {
          return null;
        }
        positions = ranges.next().positions();
      }
      var position = positions.next();
      int index = (position.getY() & 15) << 8 | (position.getZ() & 15) << 4 | position.getX() & 15;
      duplicate = visited.get(index);
      visited.set(index);

      return position;
    }
  }

  private static final class Capture {
    final Section section;
    final Long2ObjectOpenHashMap<BlockState> states = new Long2ObjectOpenHashMap<>();
    final Cells cells;
    boolean changed;

    Capture(Section section) {
      this.section = section;
      cells = new Cells(section);
      changed = section.states == null;
    }
  }

  private static final class Batch {
    final Iterator<Section> initial;
    final ArrayDeque<Section> retries = new ArrayDeque<>();
    final LinkedHashSet<Section> unavailable = new LinkedHashSet<>();
    Capture capture;
    boolean changed;

    Batch(LinkedHashSet<Section> initial) {
      this.initial = initial.iterator();
    }

    Section next() {
      return initial.hasNext() ? initial.next() : retries.pollFirst();
    }
  }

  /** Transitional two-box entry point; new callers pass the complete design. */
  public void select(ClientLevel nextLevel, long nextRevision, Box main, Box dynamic) {
    var defaults = Design.defaults();
    select(
        nextLevel,
        nextRevision,
        defaults.withMain(main).withSegments(
            List.of(new Segment(defaults.segments().getFirst().settings(), dynamic, null, null)),
            defaults.keys()));
  }

  /** A parameter-only update retains section snapshots and the complete scene. */
  public void select(ClientLevel nextLevel, long nextRevision, Design nextDesign) {
    requireClientThread();
    Objects.requireNonNull(nextLevel);
    Objects.requireNonNull(nextDesign);
    if (level == nextLevel && revision == nextRevision && nextDesign.equals(design)) {
      return;
    }
    try {
      if (nextRevision < 0) {
        throw new IllegalArgumentException("Negative selection revision");
      }
      var nextGeometry = geometry(nextDesign);
      for (var bounds : nextGeometry) {
        validateBounds(nextLevel, bounds);
      }
      if (nextLevel != level || !geometry.equals(nextGeometry)) {
        reset();
        level = nextLevel;
        geometry = nextGeometry;
        discovery = new Discovery(geometry);
      }
      revision = nextRevision;
      design = nextDesign;
      origin = nextDesign.controller() == null ? origin(geometry) : nextDesign.controller();
      compiler = null;
      recompile = true;
      invalid = false;
      error = Component.empty();
    } catch (IllegalArgumentException | ArithmeticException failure) {
      invalid = true;
      compiler = null;
      error = RangedSelectionPreviewCompiler.detail(failure);
    }
  }

  private static List<Bounds> geometry(Design design) {
    var bounds = new LinkedHashSet<Bounds>();
    add(bounds, design.main());
    for (var segment : design.segments()) {
      add(bounds, segment.body());
      add(bounds, segment.start());
      add(bounds, segment.end());
    }
    return bounds.stream()
        .sorted(
            Comparator.comparingInt(Bounds::minX).thenComparingInt(Bounds::minY).thenComparingInt(Bounds::minZ)
                .thenComparingInt(Bounds::maxX).thenComparingInt(Bounds::maxY).thenComparingInt(Bounds::maxZ))
        .toList();
  }

  private static void add(LinkedHashSet<Bounds> target, Box box) {
    if (box != null) {
      target.add(Bounds.of(box));
    }
  }

  private static void validateBounds(ClientLevel level, Bounds bounds) {
    var first = new BlockPos(bounds.minX, bounds.minY, bounds.minZ);
    var last = new BlockPos(bounds.maxX, bounds.maxY, bounds.maxZ);
    if (level.isOutsideBuildHeight(first)
        || level.isOutsideBuildHeight(last)
        || !level.getWorldBorder().isWithinBounds(first)
        || !level.getWorldBorder().isWithinBounds(last)) {
      throw new IllegalArgumentException("Selection is outside the world's build bounds");
    }
  }

  private static BlockPos origin(List<Bounds> bounds) {
    if (bounds.isEmpty()) {
      return BlockPos.ZERO;
    }
    int x = Integer.MAX_VALUE, y = Integer.MAX_VALUE, z = Integer.MAX_VALUE;
    for (var range : bounds) {
      x = Math.min(x, range.minX);
      y = Math.min(y, range.minY);
      z = Math.min(z, range.minZ);
    }

    return new BlockPos(x, y, z);
  }

  /** A dirty signal does no world scan and coalesces repeated notifications. */
  public void sectionChanged(ClientLevel source, int sectionX, int sectionY, int sectionZ) {
    requireClientThread();
    if (source != level) {
      return;
    }
    var section = sections.get(SectionPos.asLong(sectionX, sectionY, sectionZ));
    if (section != null) {
      dirty.add(section);
    }
  }

  public void blockChanged(ClientLevel source, BlockPos position) {
    sectionChanged(source, position.getX() >> 4, position.getY() >> 4, position.getZ() >> 4);
  }

  /** Load/unload wakes selected sections only; it never requests a chunk load. */
  public void chunkChanged(ClientLevel source, int chunkX, int chunkZ) {
    requireClientThread();
    if (source != level) {
      return;
    }
    var affected = chunks.get(ChunkPos.asLong(chunkX, chunkZ));
    if (affected == null) {
      return;
    }
    dirty.addAll(affected);
    if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
      compiler = null;
      recompile = true;
      if (batch != null) {
        batch.unavailable.addAll(affected);
        if (batch.capture != null && batch.capture.section.x == chunkX && batch.capture.section.z == chunkZ) {
          batch.capture = null;
        }
      }
      return;
    }
    if (batch != null) {
      for (var section : affected) {
        if (batch.unavailable.remove(section)) {
          batch.retries.addLast(section);
        }
      }
    }
  }

  /** Called by the GUI's client tick, never by render or a background worker. */
  public int advance(int budget, long deadline) {
    requireClientThread();
    if (level == null || budget <= 0 || invalid) {
      return 0;
    }
    if (Minecraft.getInstance().level != level) {
      reset();
      return 0;
    }
    int used = 0;
    while (used < budget && System.nanoTime() < deadline) {
      if (discovery != null) {
        if (!discovery.done()) {
          long key = SectionPos.asLong(discovery.x, discovery.y, discovery.z);
          var section = sections.get(key);
          if (section == null) {
            section = new Section(discovery.x, discovery.y, discovery.z);
            sections.put(key, section);
            chunks.computeIfAbsent(ChunkPos.asLong(section.x, section.z), ignored -> new ArrayList<>()).add(section);
            dirty.add(section);
          }
          section.ranges.add(discovery.bounds.get(discovery.region).section(section.x, section.y, section.z));
          discovery.next();
          used++;
          continue;
        }
        discovery = null;
      }
      if (compiler != null) {
        try {
          used += compiler.advance(budget - used, deadline);
          if (!compiler.done()) {
            break;
          }
          definition = compiler.definition();
          completedRevision = revision;
          exportSnapshot = compiler.exportSnapshot();
          compiler = null;
          error = Component.empty();
          generation++;
        } catch (RuntimeException failure) {
          compiler = null;
          error = RangedSelectionPreviewCompiler.detail(failure);
        }
        continue;
      }
      if (batch == null) {
        if (dirty.isEmpty()) {
          if (recompile) {
            beginCompile();
            used++;
            continue;
          }
          break;
        }
        batch = new Batch(dirty);
        dirty = new LinkedHashSet<>();
      }
      if (batch.capture == null) {
        var section = batch.next();
        if (section == null) {
          if (!batch.unavailable.isEmpty()) {
            break;
          }
          recompile |= batch.changed;
          batch = null;
          // Complete this snapshot before consuming notifications accumulated
          // during its capture, so continuous block updates cannot starve it.
          if (recompile) {
            beginCompile();
          }
          used++;
          continue;
        }
        if (!level.getChunkSource().hasChunk(section.x, section.z)) {
          batch.unavailable.add(section);
          used++;
          continue;
        }
        batch.capture = new Capture(section);
      }
      var capture = batch.capture;
      if (!level.getChunkSource().hasChunk(capture.section.x, capture.section.z)) {
        batch.unavailable.add(capture.section);
        batch.capture = null;
        used++;
        continue;
      }
      var position = capture.cells.next();
      if (position == null) {
        if (capture.changed) {
          capture.section.states = capture.states;
          batch.changed = true;
        }
        batch.capture = null;
      } else if (!capture.cells.duplicate) {
        var state = level.getBlockState(position);
        long key = position.asLong();
        capture.states.put(key, state);
        if (capture.section.states == null || capture.section.states.get(key) != state) {
          capture.changed = true;
        }
      }
      used++;
    }

    return used;
  }

  private void beginCompile() {
    recompile = false;
    compiler = new RangedSelectionPreviewCompiler(
        design,
        origin,
        design.controller() == null ? MachineOrientation.IDENTITY : design.orientation(),
        this::state);
  }

  private BlockState state(BlockPos position) {
    var section = sections.get(SectionPos.asLong(position.getX() >> 4, position.getY() >> 4, position.getZ() >> 4));
    var state = section == null || section.states == null ? null : section.states.get(position.asLong());
    if (state == null) {
      throw new IllegalArgumentException("Incomplete selection snapshot at " + position.toShortString());
    }
    return state;
  }

  public MachinePreviewDefinition definition() {
    return definition;
  }

  public RangedSelectionExportSnapshot exportSnapshot() {
    return exportSnapshot;
  }

  /** World refreshes may be pending while the current design is already shown. */
  public boolean currentDefinition() {
    return !invalid && definition != null && completedRevision == revision;
  }

  public long generation() {
    return generation;
  }

  public BlockPos origin() {
    return origin;
  }

  public Component error() {
    return error;
  }

  public boolean building() {
    return !invalid && (discovery != null || batch != null || compiler != null || recompile || !dirty.isEmpty());
  }

  public boolean waitingForChunks() {
    return batch != null && !batch.unavailable.isEmpty();
  }

  public void reset() {
    requireClientThread();
    level = null;
    revision = -1;
    completedRevision = -2;
    design = null;
    geometry = List.of();
    origin = BlockPos.ZERO;
    sections.clear();
    chunks.clear();
    dirty.clear();
    discovery = null;
    batch = null;
    compiler = null;
    recompile = false;
    invalid = false;
    definition = null;
    exportSnapshot = null;
    error = Component.empty();
  }

  private static void requireClientThread() {
    if (!Minecraft.getInstance().isSameThread()) {
      throw new IllegalStateException("Selection capture requires the client thread");
    }
  }
}
