package com.lw.modularmachine.machine.assembly;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.assembly.AssemblyPlan;
import com.lw.modularmachine.api.assembly.AssemblyPage;
import com.lw.modularmachine.api.assembly.AssemblyPart;
import com.lw.modularmachine.api.assembly.AssemblyCandidate;
import com.lw.modularmachine.api.assembly.MachineAssemblyApi;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.network.ProjectionPayloads;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;
import java.util.Set;

/**
 * Owns preparing/ready world projections, exact requests and bounded packet
 * delivery.
 */
@ApiStatus.Internal
final class AssemblyProjectionCoordinator {
  private final Map<UUID, AssemblyProjection> projections = new HashMap<>();
  private final Map<UUID, PendingProjection> pendingProjections = new HashMap<>();
  private long request;

  private static final class PendingProjection {
    final StructureService.StructureSubject owner;
    final ServerLevel level;
    final MachineDefinition definition;
    final MachineOrientation orientation;
    final BlockPos origin;
    final long epoch, request, controllerRevision;
    final ProjectionPreparation preparation;
    final Map<String, Integer> lengths;
    final int layers;
    PendingProjection(StructureService.StructureSubject owner, ServerLevel level, MachineDefinition definition, MachineOrientation orientation, Map<String, Integer> lengths, long request, int layers) {
      this.owner = owner;
      this.level = level;
      this.definition = definition;
      this.orientation = orientation;
      this.request = request;
      this.layers = layers;
      this.lengths = Map.copyOf(lengths);
      origin = owner.structurePosition().immutable();
      epoch = StructureService.definitionEpoch();
      controllerRevision = StructureService.controllerProjectionRevision(owner);
      preparation = new ProjectionPreparation(definition, epoch, origin, orientation, lengths);
    }
  }

  void request(ServerPlayer player, StructureService.StructureSubject owner, MachineDefinition definition, MachineOrientation orientation, Map<String, Integer> lengths, int layers) {
    var id = player.getUUID();
    var machine = definition.id();
    var pending = pendingProjections.get(id);
    if (pending != null
        && pending.owner == owner
        && pending.definition == definition
        && pending.lengths.equals(lengths)
        && pending.layers == layers
        && pending.orientation == orientation
        && pending.epoch == StructureService.definitionEpoch()) {
      return;
    }
    var previous = projections.get(id);
    if (previous != null
        && previous.owner == owner
        && previous.pattern.machine().equals(machine)
        && previous.pattern.dynamicLengths().equals(lengths)
        && previous.pattern.orientation() == orientation
        && previous.pattern.epoch() == StructureService.definitionEpoch()) {
      return;
    }
    projections.remove(id);
    removePending(id);
    ProjectionPayloads.clear(player, request = Math.incrementExact(request));
    long selectedRequest = request = Math.incrementExact(request);
    pendingProjections.put(
        id,
        new PendingProjection(owner, player.serverLevel(), definition, orientation, lengths, selectedRequest, layers));
    // Header lets the client cancel by distance even during preparation.
    ProjectionPayloads.begin(
        player,
        selectedRequest,
        StructureService.definitionEpoch(),
        machine,
        owner.structurePosition(),
        Math.max(1, definition.parts().size()));
  }

  /**
   * Only the same current owner, definition and orientation may supply lengths.
   */
  Map<String, Integer> lengths(ServerPlayer player, StructureService.StructureSubject owner, MachineDefinition definition) {
    var pending = pendingProjections.get(player.getUUID());
    if (pending != null && pending.owner == owner && pending.definition == definition && current(player, pending)) {
      return pending.lengths;
    }
    var projection = projections.get(player.getUUID());
    if (projection != null
        && projection.owner == owner
        && projection.pattern.sourceDefinition() == definition
        && projection.current(player)) {
      return projection.pattern.dynamicLengths();
    }
    return null;
  }

  @ApiStatus.Internal
  Optional<AssemblyPlan> inspect(ServerPlayer player) {
    ServerThreadGuard.require(player.serverLevel(), "Projection plan inspected");
    var projection = projections.get(player.getUUID());
    if (projection == null || !projection.current(player)) {
      return Optional.empty();
    }
    var pattern = projection.pattern;
    return Optional.of(
        new AssemblyPlan(
            projection.request,
            projection.level.dimension().location(),
            pattern.origin(),
            pattern.machine(),
            pattern.epoch(),
            pattern.orientation(),
            pattern.dynamicLengths(),
            pattern.selectedModifierGroups(),
            pattern.parts().size()));
  }

  @ApiStatus.Internal
  boolean isCurrent(ServerPlayer player, long expectedRequest) {
    ServerThreadGuard.require(player.serverLevel(), "Projection plan checked");
    var projection = projections.get(player.getUUID());
    return expectedRequest >= 0
        && projection != null
        && projection.request == expectedRequest
        && projection.current(player);
  }

  @ApiStatus.Internal
  Optional<AssemblyPage> readPage(ServerPlayer player, long expectedRequest, int start, int limit) {
    ServerThreadGuard.require(player.serverLevel(), "Projection plan page read");
    if (expectedRequest < 0 || start < 0 || limit < 1 || limit > MachineAssemblyApi.MAX_PAGE_PARTS) {
      throw new IllegalArgumentException("Invalid projection plan page request");
    }
    var projection = projections.get(player.getUUID());
    if (projection == null
        || projection.request != expectedRequest
        || !projection.current(player)
        || start > projection.pattern.parts().size()) {
      return Optional.empty();
    }
    int total = projection.pattern.parts().size();
    int end = (int) Math.min((long) total, (long) start + limit);
    var parts = new ArrayList<AssemblyPart>(end - start);
    for (int index = start; index < end; index++) {
      var source = projection.pattern.parts().get(index);
      var alternatives = source.rule().alternatives();
      var candidates = new ArrayList<AssemblyCandidate>(source.states().size());
      for (int candidate = 0; candidate < source.states().size(); candidate++) {
        List<String> properties;
        if (alternatives.isEmpty()) {
          properties = List.of();
        } else {
          properties = alternatives.get(candidate).properties().keySet().stream().map(property -> property.getName())
              .sorted().toList();
        }
        candidates.add(new AssemblyCandidate(source.states().get(candidate), properties));
      }
      parts.add(new AssemblyPart(source.position(), candidates));
    }
    return Optional.of(new AssemblyPage(expectedRequest, start, total, parts));
  }

  void cancel(ServerPlayer player) {
    if (!AssemblyService.canEdit(player)) {
      return;
    }
    projections.remove(player.getUUID());
    removePending(player.getUUID());
    ProjectionPayloads.clear(player, request = Math.incrementExact(request));
    player.displayClientMessage(ProjectionMessages.cancelled(), true);
  }

  /**
   * Client may release only its own exact projection; no world or build writes.
   */
  void stop(ServerPlayer player, long expectedRequest) {
    ServerThreadGuard.require(player.serverLevel(), "Projection cancelled");
    removeRequest(player.getUUID(), expectedRequest);
  }

  private boolean removeRequest(UUID player, long expectedRequest) {
    var job = projections.get(player);
    if (job != null && job.request == expectedRequest) {
      projections.remove(player);
      return true;
    }
    var pending = pendingProjections.get(player);
    if (pending != null && pending.request == expectedRequest) {
      removePending(player);
      return true;
    }
    return false;
  }

  private void invalidate(ServerPlayer player, long expectedRequest) {
    if (removeRequest(player.getUUID(), expectedRequest)) {
      ProjectionPayloads.clear(player, expectedRequest);
      player.displayClientMessage(ProjectionMessages.cancelled(), true);
    }
  }

  void removePending(UUID player) {
    var pending = pendingProjections.remove(player);
    if (pending != null) {
      pending.preparation.cancel();
    }
  }

  void prepare(ServerPlayer player, long deadline) {
    var pending = pendingProjections.get(player.getUUID());
    if (pending == null) {
      return;
    }
    if (!current(player, pending)) {
      invalidate(player, pending.request);
      return;
    }
    if (System.nanoTime() >= deadline) {
      return;
    }
    try {
      var ready = pending.preparation.advance(deadline);
      if (pendingProjections.get(player.getUUID()) != pending) {
        return;
      }
      if (!current(player, pending)) {
        invalidate(player, pending.request);
        return;
      }
      if (ready != null) {
        var job = new AssemblyProjection(
            pending.owner,
            ready.pattern(),
            pending.level,
            pending.request,
            ready.entries());
        ModularMachine.LOGGER
            .debug("Projection {} ready: {} entries at {}", pending.request, ready.entries().size(), pending.origin);
        pendingProjections.remove(player.getUUID());
        projections.put(player.getUUID(), job);
        player.displayClientMessage(ProjectionMessages.length(pending.layers), true);
      }
    } catch (RuntimeException failure) {
      ModularMachine.LOGGER.warn("Projection preparation failed at {}", pending.origin, failure);
      invalidate(player, pending.request);
    }
  }

  private static boolean current(ServerPlayer player, PendingProjection pending) {
    return AssemblyAccess.validPlayer(player)
        && player.level() == pending.level
        && pending.epoch == StructureService.definitionEpoch()
        && pending.definition == StructureService.definition(pending.definition.id())
        && pending.controllerRevision == StructureService.controllerProjectionRevision(pending.owner)
        && !pending.owner.structureRemoved()
        && pending.owner.structureSchemaSupported()
        && pending.level.hasChunkAt(pending.origin)
        && pending.level.getBlockEntity(pending.origin) == pending.owner
        && pending.level.mayInteract(player, pending.origin)
        && pending.definition.id().equals(AssemblyAccess.selectedMachine(pending.owner, false))
        && pending.orientation == pending.owner.structureOrientation(pending.level);
  }

  int send(ServerPlayer player, long projectionDeadline, int packetBudget) {
    int packets = 0;
    var projection = projections.get(player.getUUID());
    if (projection != null && !projection.current(player)) {
      invalidate(player, projection.request);
    } else if (projection != null) {
      for (int sent = 0; sent < packetBudget
          && packets < packetBudget
          && projection.sent < projection.entries.size()
          && System.nanoTime() < projectionDeadline; sent++, packets++) {
        projection.sent = ProjectionPayloads
            .sendChunk(player, projection.pattern, projection.request, projection.entries, projection.sent);
      }
    }
    return packets;
  }

  Set<UUID> players() {
    return projections.keySet();
  }
  Set<UUID> pendingPlayers() {
    return pendingProjections.keySet();
  }
  void remove(UUID player) {
    projections.remove(player);
  }
  void clear() {
    projections.clear();
    pendingProjections.values().forEach(pending -> pending.preparation.cancel());
    pendingProjections.clear();
  }
}
