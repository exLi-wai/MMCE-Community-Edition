package com.lw.modularmachine.machine.structure;

import com.lw.modularmachine.block.ParallelControllerBlock;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.blockentity.ParallelControllerBlockEntity;
import com.lw.modularmachine.blockentity.SmartInterfaceBlockEntity;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.orientation.OrientationCompilation;
import com.lw.modularmachine.machine.orientation.PropertySpace;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Immutable absolute layout. Production uses Builder; synchronous constructors
 * are convenience APIs.
 */
public final class CompiledStructure {
  public final MachineDefinition definition;
  public final BlockPos origin;
  public final MachineOrientation orientation;

  /** Compatibility view; never use this alone for a production ticket. */
  public final Direction facing;

  private final List<BlockPos> positions;
  private final Map<Long, Integer> sectionGroups;
  private final Map<BlockPos, Integer> contentGroups;
  private final int[][] groups;
  private final BlockPos energyInputPosition;
  private final BlockPos energyOutputPosition;
  private final int minX, minY, minZ, maxX, maxY, maxZ;
  private final boolean[] rotateProperties;
  private final Rotation inverseRotation;
  private final List<MachineDefinition.Rule> resolvedRules;
  private final StructureModifierTracker.Plan modifierPlan;

  public CompiledStructure(MachineDefinition definition, BlockPos origin, Direction facing) {
    this(definition, origin, MachineOrientation.of(facing, 0));
  }

  public CompiledStructure(MachineDefinition definition, BlockPos origin, MachineOrientation orientation) {
    this(prepareFully(definition, origin, orientation));
  }

  private CompiledStructure(Data data) {
    definition = data.definition;
    origin = data.origin;
    orientation = data.orientation;
    facing = orientation.front();
    positions = data.positions;
    sectionGroups = data.sections;
    contentGroups = data.contents;
    groups = data.groups;
    energyInputPosition = data.input;
    energyOutputPosition = data.output;
    minX = data.minX;
    minY = data.minY;
    minZ = data.minZ;
    maxX = data.maxX;
    maxY = data.maxY;
    maxZ = data.maxZ;
    rotateProperties = data.rotateProperties;
    resolvedRules = data.rules;
    modifierPlan = data.modifiers;
    inverseRotation = definition.parts().stream().anyMatch(
        part -> part.propertiesSpace() == PropertySpace.LEGACY
            && part.rule().alternatives().stream()
                .anyMatch(alternative -> !alternative.properties().isEmpty())) ? switch (facing) {
                  case NORTH -> Rotation.NONE;
                  case EAST -> Rotation.COUNTERCLOCKWISE_90;
                  case SOUTH -> Rotation.CLOCKWISE_180;
                  case WEST -> Rotation.CLOCKWISE_90;
                  default -> throw new IllegalArgumentException("Legacy definitions only support horizontal roll zero");
                } : Rotation.NONE;
  }

  private static Data prepareFully(MachineDefinition definition, BlockPos origin, MachineOrientation orientation) {
    Builder builder = new Builder(definition, origin, orientation);
    while (!builder.complete()) {
      if (Thread.currentThread().isInterrupted()) {
        throw new CancellationException();
      }
      builder.advance(4096);
    }

    return builder.data;
  }

  public static Builder prepare(MachineDefinition definition, BlockPos origin, MachineOrientation orientation) {
    return new Builder(definition, origin, orientation);
  }

  /**
   * Pure relocation of a shared origin template; no rule preparation or world
   * access.
   */
  CompiledStructure relocate(BlockPos target) {
    if (target.equals(origin)) {
      return this;
    }
    var shifted = new ArrayList<BlockPos>(positions.size());
    var sections = new LinkedHashMap<Long, Integer>();
    var contents = new LinkedHashMap<BlockPos, Integer>();
    var indices = new ArrayList<List<Integer>>();
    int lowX = Integer.MAX_VALUE, lowY = Integer.MAX_VALUE, lowZ = Integer.MAX_VALUE;
    int highX = Integer.MIN_VALUE, highY = Integer.MIN_VALUE, highZ = Integer.MIN_VALUE;
    BlockPos input = null, output = null;
    for (int index = 0; index < positions.size(); index++) {
      if ((index & 4095) == 0 && Thread.currentThread().isInterrupted()) {
        throw new CancellationException();
      }
      var local = positions.get(index).subtract(origin);
      var pos = new BlockPos(
          Math.addExact(target.getX(), local.getX()),
          Math.addExact(target.getY(), local.getY()),
          Math.addExact(target.getZ(), local.getZ()));
      shifted.add(pos);
      long section = SectionPos.asLong(pos);
      Integer group = sections.get(section);
      if (group == null) {
        group = indices.size();
        sections.put(section, group);
        indices.add(new ArrayList<>());
      }
      indices.get(group).add(index);
      var part = definition.parts().get(index);
      if (part.field() != null) {
        contents.put(pos, group);
      }
      if (part.rule().port() == PortKind.ENERGY_INPUT) {
        input = pos;
      }
      if (part.rule().port() == PortKind.ENERGY_OUTPUT) {
        output = pos;
      }
      lowX = Math.min(lowX, pos.getX());
      lowY = Math.min(lowY, pos.getY());
      lowZ = Math.min(lowZ, pos.getZ());
      highX = Math.max(highX, pos.getX());
      highY = Math.max(highY, pos.getY());
      highZ = Math.max(highZ, pos.getZ());
    }
    return new CompiledStructure(
        new Data(
            definition,
            target.immutable(),
            orientation,
            List.copyOf(shifted),
            Map.copyOf(sections),
            Map.copyOf(contents),
            indices.stream().map(values -> values.stream().mapToInt(Integer::intValue).toArray()).toArray(int[][]::new),
            input,
            output,
            rotateProperties,
            resolvedRules,
            modifierPlan,
            lowX,
            lowY,
            lowZ,
            highX,
            highY,
            highZ));
  }

  BlockState snapshotState(int index, BlockState state) {
    return rotateProperties[index] ? state.rotate(inverseRotation) : state;
  }

  CheckResult matchSnapshot(int index, BlockState state, CheckResult details) {
    if (state == null) {
      return CheckResult.UNAVAILABLE;
    }
    return resolvedRules.get(index).matches(state) ? details : CheckResult.MISMATCH;
  }

  private record Data(
      MachineDefinition definition,
      BlockPos origin,
      MachineOrientation orientation,
      List<BlockPos> positions,
      Map<Long, Integer> sections,
      Map<BlockPos, Integer> contents,
      int[][] groups,
      BlockPos input,
      BlockPos output,
      boolean[] rotateProperties,
      List<MachineDefinition.Rule> rules,
      StructureModifierTracker.Plan modifiers,
      int minX,
      int minY,
      int minZ,
      int maxX,
      int maxY,
      int maxZ) {
  }

  /**
   * Pure preparation, resumable before any subscriptions or production authority
   * are published.
   */
  public static final class Builder {
    private final MachineDefinition definition;
    private final BlockPos origin;
    private final MachineOrientation orientation;
    private final OrientationCompilation.RulePreparation rules;
    private final StructureModifierTracker.Builder modifiers;
    private final List<BlockPos> positions = new ArrayList<>();
    private final Map<Long, Integer> sections = new LinkedHashMap<>();
    private final Map<BlockPos, Integer> contents = new LinkedHashMap<>();
    private final List<List<Integer>> groupIndices = new ArrayList<>();
    private final boolean[] rotateProperties;
    private int cursor;
    private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
    private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
    private BlockPos input, output;
    private Data data;
    private CompiledStructure result;

    private Builder(MachineDefinition definition, BlockPos origin, MachineOrientation orientation) {
      this.definition = definition;
      this.origin = origin.immutable();
      this.orientation = orientation;
      rules = definition.orientationCompilation().prepareRules(orientation);
      modifiers = new StructureModifierTracker.Builder(definition, orientation);
      rotateProperties = new boolean[definition.parts().size()];
    }

    public boolean complete() {
      return data != null;
    }

    public int preparedPositions() {
      return cursor;
    }

    public CompiledStructure result() {
      if (!complete()) {
        throw new IllegalStateException("Layout is still preparing");
      }
      if (result == null) {
        result = new CompiledStructure(data);
      }
      return result;
    }

    public int advance(int budget) {
      if (budget < 0) {
        throw new IllegalArgumentException("Negative preparation budget");
      }
      if (complete() || budget == 0) {
        return 0;
      }
      int used = rules.advance(budget);
      used += modifiers.advance(budget - used);
      while (used < budget && cursor < definition.parts().size()) {
        var part = definition.parts().get(cursor);
        BlockPos offset = orientation.toWorldOffset(part.offset());
        BlockPos position = new BlockPos(
            Math.addExact(origin.getX(), offset.getX()),
            Math.addExact(origin.getY(), offset.getY()),
            Math.addExact(origin.getZ(), offset.getZ()));
        positions.add(position);
        long section = SectionPos.asLong(position);
        Integer group = sections.get(section);
        if (group == null) {
          group = groupIndices.size();
          sections.put(section, group);
          groupIndices.add(new ArrayList<>());
        }
        groupIndices.get(group).add(cursor);
        if (part.field() != null) {
          contents.put(position, group);
        }
        if (part.rule().port() == PortKind.ENERGY_INPUT) {
          input = position;
        }
        if (part.rule().port() == PortKind.ENERGY_OUTPUT) {
          output = position;
        }
        rotateProperties[cursor] = part.propertiesSpace() == PropertySpace.LEGACY
            && orientation != MachineOrientation.IDENTITY
            && part.rule().alternatives().stream().anyMatch(alt -> !alt.properties().isEmpty());
        minX = Math.min(minX, position.getX());
        minY = Math.min(minY, position.getY());
        minZ = Math.min(minZ, position.getZ());
        maxX = Math.max(maxX, position.getX());
        maxY = Math.max(maxY, position.getY());
        maxZ = Math.max(maxZ, position.getZ());
        cursor++;
        used++;
      }
      if (rules.complete() && modifiers.complete() && cursor == definition.parts().size()) {
        // Bounded final freezing: at most 4096 entries, no world reads or external
        // callbacks.
        int[][] groups = groupIndices.stream().map(indices -> indices.stream().mapToInt(Integer::intValue).toArray())
            .toArray(int[][]::new);
        data = new Data(
            definition,
            origin,
            orientation,
            List.copyOf(positions),
            Map.copyOf(sections),
            Map.copyOf(contents),
            groups,
            input,
            output,
            rotateProperties,
            rules.result(),
            modifiers.result(),
            minX,
            minY,
            minZ,
            maxX,
            maxY,
            maxZ);
      }
      return used;
    }
  }

  /**
   * Kept for old callers; this method still represents horizontal roll-zero
   * rotation only.
   */
  public static BlockPos rotate(BlockPos position, Direction facing) {
    if (facing.getAxis() == Direction.Axis.Y) {
      throw new IllegalArgumentException("Only horizontal controllers are supported");
    }
    return MachineOrientation.of(facing, 0).toWorldOffset(position);
  }

  public CheckResult checkBase(ServerLevel level, int index) {
    BlockPos pos = positions.get(index);
    if (level.isOutsideBuildHeight(pos) || !level.hasChunkAt(pos)) {
      return CheckResult.UNAVAILABLE;
    }
    var part = definition.parts().get(index);
    var state = level.getBlockState(pos);
    if (rotateProperties[index]) {
      state = state.rotate(inverseRotation);
    }
    if (!resolvedRules.get(index).matches(state)) {
      return CheckResult.MISMATCH;
    }
    if (part.rule().port() != null) {
      if (!(level.getBlockEntity(pos) instanceof MachinePortBlockEntity port)) {
        return CheckResult.UNAVAILABLE;
      }
      if (!port.schemaSupported()) {
        return CheckResult.UNSUPPORTED;
      }
    }
    if (part.field() != null
        && (!(level.getBlockEntity(pos) instanceof MachinePortBlockEntity port) || !part.field().matches(port))) {
      return CheckResult.MISMATCH;
    }
    if (!part.interfaceType().isEmpty()) {
      if (!(level.getBlockEntity(pos) instanceof SmartInterfaceBlockEntity smart)) {
        return CheckResult.UNAVAILABLE;
      }
      if (!smart.schemaSupported()) {
        return CheckResult.UNSUPPORTED;
      }
    }
    if (state.getBlock() instanceof ParallelControllerBlock) {
      if (!(level.getBlockEntity(pos) instanceof ParallelControllerBlockEntity parallel)) {
        return CheckResult.UNAVAILABLE;
      }
      if (!parallel.schemaSupported()) {
        return CheckResult.UNSUPPORTED;
      }
    }

    return CheckResult.MATCH;
  }

  /**
   * Independent single-position reference; production uses its published group
   * proof.
   */
  public CheckResult check(ServerLevel level, int index) {
    var base = checkBase(level, index);
    if (base != CheckResult.MISMATCH) {
      return base;
    }
    var reference = modifierReference(level);
    if (reference.failure() == VerificationFailure.CHUNK_UNAVAILABLE) {
      return CheckResult.UNAVAILABLE;
    }
    if (reference.failure() != VerificationFailure.NONE) {
      throw new IllegalStateException("Structure modifier reference failed: " + reference.failure());
    }

    return modifierPlan.allows(index, reference) ? CheckResult.MATCH : CheckResult.MISMATCH;
  }

  StructureModifierTracker.Plan modifierPlan() {
    return modifierPlan;
  }

  /**
   * Selection-only incremental scan; its result never authorizes a resource
   * commit.
   */
  public Discovery discover(long now) {
    return new Discovery(now);
  }

  public final class Discovery {
    private final StructureModifierTracker modifiers;
    private int index;
    private boolean failed;

    private Discovery(long now) {
      modifiers = new StructureModifierTracker(CompiledStructure.this, now);
    }

    public int advance(ServerLevel level, int budget, long now, int timeout) {
      int used = modifiers.advance(level, budget, now, timeout);
      if (modifiers.invalid()) {
        failed = true;
      }
      while (!failed && modifiers.canCommit() && used < budget && index < definition.parts().size()) {
        CheckResult result = checkBase(level, index);
        failed = result != CheckResult.MATCH && (result != CheckResult.MISMATCH || !modifiers.allows(index));
        index++;
        used++;
      }
      return used;
    }

    public boolean complete() {
      return failed || index == definition.parts().size();
    }

    public boolean matched() {
      return !failed && complete();
    }
  }

  public StructureModifierTracker.Reference modifierReference(ServerLevel level) {
    return modifierPlan.reference(level, this);
  }

  public FullScanReference.Result fullScan(ServerLevel level) {
    return fullScan(level, modifierReference(level));
  }

  public FullScanReference.Result fullScan(ServerLevel level, StructureModifierTracker.Reference reference) {
    if (!reference.belongsTo(modifierPlan)) {
      throw new IllegalArgumentException("Reference belongs to a different structure");
    }
    if (reference.failure() != VerificationFailure.NONE) {
      return new FullScanReference.Result(
          VerificationState.INVALID,
          reference.failure(),
          reference.failedPart(),
          reference.checks());
    }
    var result = FullScanReference.scan(definition.parts().size(), index -> {
      var base = checkBase(level, index);

      return base == CheckResult.MISMATCH && modifierPlan.allows(index, reference) ? CheckResult.MATCH : base;
    });

    return new FullScanReference.Result(
        result.state(),
        result.failure(),
        result.failedIndex(),
        result.checks() + reference.checks());
  }

  public int[][] groups() {
    return Arrays.stream(groups).map(int[]::clone).toArray(int[][]::new);
  }

  public Set<Long> sections() {
    return sectionGroups.keySet();
  }

  public int groupFor(BlockPos pos) {
    return sectionGroups.getOrDefault(SectionPos.asLong(pos), -1);
  }

  public boolean contains(BlockPos pos) {
    return pos.getX() >= minX
        && pos.getX() <= maxX
        && pos.getY() >= minY
        && pos.getY() <= maxY
        && pos.getZ() >= minZ
        && pos.getZ() <= maxZ;
  }

  public BlockPos position(int index) {
    return positions.get(index);
  }

  public BlockPos energyInputPosition() {
    return energyInputPosition;
  }

  public BlockPos energyOutputPosition() {
    return energyOutputPosition;
  }

  public boolean watchesContentsAt(BlockPos pos) {
    return contentGroups.containsKey(pos);
  }

  public boolean chunksAvailable(ServerLevel level) {
    for (long section : sectionGroups.keySet()) {
      if (!level.hasChunk(SectionPos.x(section), SectionPos.z(section))) {
        return false;
      }
    }
    return true;
  }
}
