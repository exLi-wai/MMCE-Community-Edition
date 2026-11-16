package com.lw.modularmachine.machine.assembly;

import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.network.ProjectionPayloads;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;

/**
 * Ready world projection and its delivery cursor; no prepaid construction
 * state.
 */
@ApiStatus.Internal
final class AssemblyProjection {
  final StructureService.StructureSubject owner;
  final AssemblyPattern pattern;
  final ServerLevel level;
  final long projectionRevision;
  final long request;
  final List<ProjectionPayloads.Entry> entries;
  int sent;

  AssemblyProjection(StructureService.StructureSubject owner, AssemblyPattern pattern, ServerLevel level, long request, List<ProjectionPayloads.Entry> entries) {
    this.owner = owner;
    this.pattern = pattern;
    this.level = level;
    this.request = request;
    this.entries = entries;
    projectionRevision = StructureService.controllerProjectionRevision(owner);
  }

  boolean current(ServerPlayer player) {
    if (!AssemblyAccess.validPlayer(player)
        || player.level() != level
        || pattern.epoch() != StructureService.definitionEpoch()
        || pattern.sourceDefinition() != StructureService.definition(pattern.machine())
        || !level.hasChunkAt(pattern.origin())
        || !level.mayInteract(player, pattern.origin())
        || projectionRevision != StructureService.controllerProjectionRevision(owner)) {
      return false;
    }
    return !owner.structureRemoved()
        && owner.structureSchemaSupported()
        && level.getBlockEntity(pattern.origin()) == owner
        && pattern.machine().equals(AssemblyAccess.selectedMachine(owner, false))
        && pattern.orientation() == owner.structureOrientation(level);
  }

}
