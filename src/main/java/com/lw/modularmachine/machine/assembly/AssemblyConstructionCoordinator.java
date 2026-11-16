package com.lw.modularmachine.machine.assembly;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.machine.structure.StructureService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import org.jetbrains.annotations.ApiStatus;

/**
 * One survival job per player, shared fair work slicing and independent report
 * delivery.
 */
@ApiStatus.Internal
final class AssemblyConstructionCoordinator implements SurvivalAssembly.Host {
  interface Host {
    Map<String, Integer> projectedLengths(ServerPlayer player, StructureService.StructureSubject owner, MachineDefinition definition);
  }
  private final Host host;
  private final SurvivalAssembly survival = new SurvivalAssembly(this);
  private final Map<UUID, AssemblyJob> jobs = new LinkedHashMap<>();
  private final Map<UUID, Warnings> warnings = new LinkedHashMap<>();
  private final Map<UUID, PendingConstruction> pendingConstructions = new HashMap<>();
  private int assemblyCursor;
  private static final long BUILD_SLICE_NANOS = 250_000L;

  AssemblyConstructionCoordinator(Host host) {
    this.host = host;
  }

  private static final class PendingConstruction {
    final StructureService.StructureSubject owner;
    final ServerLevel level;
    final MachineDefinition definition;
    final MachineOrientation orientation;
    final BlockPos origin;
    final long epoch;
    final InteractionHand hand;
    final boolean creative;
    final Map<String, Integer> lengths;
    final AssemblyPreparation preparation;

    PendingConstruction(ServerPlayer player, StructureService.StructureSubject owner, MachineDefinition definition, MachineOrientation orientation, Map<String, Integer> lengths, InteractionHand hand) {
      this.owner = owner;
      this.definition = definition;
      this.orientation = orientation;
      this.hand = hand;
      this.lengths = Map.copyOf(lengths);
      level = player.serverLevel();
      origin = owner.structurePosition().immutable();
      epoch = StructureService.definitionEpoch();
      creative = player.hasInfiniteMaterials();
      preparation = new AssemblyPreparation(definition, epoch, origin, orientation, lengths);
    }
  }

  private static final class Warnings {
    final AssemblyReport.Delivery delivery;
    long lastTick = -1;

    Warnings(AssemblyReport.Delivery delivery) {
      this.delivery = delivery;
    }
  }

  void request(ServerPlayer player, StructureService.StructureSubject owner, MachineDefinition definition, MachineOrientation orientation, Map<String, Integer> lengths, InteractionHand hand) {
    var id = player.getUUID();
    var pending = pendingConstructions.get(id);
    boolean creative = player.hasInfiniteMaterials();
    if (pending != null
        && pending.owner == owner
        && pending.level == player.level()
        && pending.definition == definition
        && pending.epoch == StructureService.definitionEpoch()
        && pending.orientation == orientation
        && pending.lengths.equals(lengths)
        && pending.creative == creative) {
      return;
    }
    var previous = jobs.get(id);
    if (!creative
        && previous != null
        && previous.owner == owner
        && previous.pattern.sourceDefinition() == definition
        && previous.pattern.epoch() == StructureService.definitionEpoch()
        && previous.pattern.orientation() == orientation
        && previous.pattern.dynamicLengths().equals(lengths)) {
      return;
    }
    removePending(id);
    removeJob(id);
    pendingConstructions.put(id, new PendingConstruction(player, owner, definition, orientation, lengths, hand));
  }

  @Override
  public void cancel(ServerPlayer player) {
    removeJob(player.getUUID());
    removePending(player.getUUID());
  }

  @Override
  public void finish(ServerPlayer player, AssemblyJob job) {
    if (jobs.get(player.getUUID()) != job) {
      return;
    }
    var delivery = job.report.takeDelivery(player, job.materialDemand);
    removeJob(player.getUUID(), job);
    removeWarnings(player.getUUID());
    if (!delivery.done()) {
      warnings.put(player.getUUID(), new Warnings(delivery));
    }
  }

  void removeWarnings(UUID player) {
    var warning = warnings.remove(player);
    if (warning != null) {
      warning.delivery.cancel();
    }
  }

  @Override
  public void removeJob(UUID player) {
    var job = jobs.remove(player);
    if (job != null) {
      job.cancel();
    }
  }

  @Override
  public void removeJob(UUID player, AssemblyJob job) {
    if (jobs.remove(player, job)) {
      job.cancel();
    }
  }

  void removePending(UUID player) {
    var pending = pendingConstructions.remove(player);
    if (pending != null) {
      pending.preparation.cancel();
    }
  }

  void advance(MinecraftServer server) {
    var identities = new LinkedHashSet<>(jobs.keySet());
    identities.addAll(warnings.keySet());
    var players = new ArrayList<>(identities);
    if (players.isEmpty()) {
      return;
    }
    int start = Math.floorMod(assemblyCursor, players.size());
    int visits = 0, idle = 0;
    int remaining = MachineConfig.assemblyWorkPerTick();
    long deadline = System.nanoTime() + MachineConfig.assemblyTimeBudgetNanos();
    while (remaining > 0 && System.nanoTime() < deadline) {
      var id = players.get((start + visits++) % players.size());
      var player = server.getPlayerList().getPlayer(id);
      int used;
      if (!jobs.containsKey(id) && !warnings.containsKey(id)) {
        used = 0;
      } else if (player == null) {
        removeJob(id);
        removeWarnings(id);
        used = 1;
      } else {
        long sliceDeadline = Math.min(deadline, System.nanoTime() + BUILD_SLICE_NANOS);
        int workBudget = Math.min(remaining, MachineConfig.assemblySliceWork());
        used = sendWarnings(player, workBudget, sliceDeadline);
        if (used < workBudget && System.nanoTime() < sliceDeadline) {
          used += survival.advance(player, workBudget - used, sliceDeadline);
        }
      }
      remaining -= used;
      idle = used == 0 ? idle + 1 : 0;
      if (idle >= players.size()) {
        break;
      }
    }
    assemblyCursor = (start + visits) % players.size();
  }

  private int sendWarnings(ServerPlayer player, int workBudget, long deadline) {
    var warning = warnings.get(player.getUUID());
    long tick = player.server.getTickCount();
    if (warning == null || warning.lastTick == tick) {
      return 0;
    }
    warning.lastTick = tick;
    var sent = warning.delivery.send(player, 4, deadline, workBudget);
    if (sent.done()) {
      if (warnings.get(player.getUUID()) == warning) {
        removeWarnings(player.getUUID());
      }
    }

    return sent.work();
  }

  void prepare(ServerPlayer player, long deadline) {
    var id = player.getUUID();
    var pending = pendingConstructions.get(id);
    if (pending == null) {
      return;
    }
    if (!current(player, pending)) {
      cancel(player);
      return;
    }
    if (System.nanoTime() >= deadline) {
      return;
    }
    try {
      var pattern = pending.preparation.advance(deadline);
      if (pendingConstructions.get(id) != pending) {
        return;
      }
      if (!current(player, pending)) {
        cancel(player);
        return;
      }
      if (pattern == null) {
        return;
      }
      pendingConstructions.remove(id, pending);
      if (pending.creative) {
        // The world mutation retains the existing creative all-or-nothing batch.
        AssemblyCreativePlacement.build(player, pattern);
        removeWarnings(id);
      } else {
        jobs.put(id, new AssemblyJob(pending.owner, pattern, pending.level, pending.hand));
      }
    } catch (RuntimeException failure) {
      if (pendingConstructions.get(id) == pending) {
        removePending(id);
      }
      ModularMachine.LOGGER.warn("Construction preparation failed at {}", pending.origin, failure);
      Component reason;
      if (failure instanceof AssemblyCreativePlacement.Failure creativeFailure) {
        reason = creativeFailure.reason;
      } else {
        reason = Component.translatable("message.mmcece.assembly.reason.unexpected");
      }
      removeWarnings(id);
      player.sendSystemMessage(Component.translatable("message.mmcece.assembly.failed", reason));
    }
  }

  private boolean current(ServerPlayer player, PendingConstruction pending) {
    if (!AssemblyAccess.validPlayer(player)
        || player.level() != pending.level
        || pending.owner.structureLevel() != pending.level
        || player.hasInfiniteMaterials() != pending.creative
        || pending.epoch != StructureService.definitionEpoch()
        || pending.definition != StructureService.definition(pending.definition.id())
        || pending.owner.structureRemoved()
        || !pending.owner.structureSchemaSupported()
        || !pending.level.hasChunkAt(pending.origin)
        || pending.level.getBlockEntity(pending.origin) != pending.owner
        || !pending.level.mayInteract(player, pending.origin)
        || !pending.definition.id().equals(AssemblyAccess.selectedMachine(pending.owner, true))
        || pending.orientation != pending.owner.structureOrientation(pending.level)) {
      return false;
    }
    var projected = host.projectedLengths(player, pending.owner, pending.definition);
    if (projected != null) {
      return projected.equals(pending.lengths);
    }
    return true;
  }

  @Override
  public AssemblyJob job(UUID player) {
    return jobs.get(player);
  }

  Set<UUID> players() {
    return jobs.keySet();
  }
  Set<UUID> pendingPlayers() {
    return pendingConstructions.keySet();
  }

  void invalidateLengths(UUID id, StructureService.StructureSubject owner, MachineDefinition definition, Map<String, Integer> lengths) {
    var building = pendingConstructions.get(id);
    if (building != null
        && building.owner == owner
        && building.definition == definition
        && !building.lengths.equals(lengths)) {
      removePending(id);
    }
    var construction = jobs.get(id);
    if (construction != null
        && construction.owner == owner
        && construction.pattern.sourceDefinition() == definition
        && !construction.pattern.dynamicLengths().equals(lengths)) {
      removeJob(id);
    }
  }

  void clearJobsAndWarnings() {
    jobs.values().forEach(AssemblyJob::cancel);
    jobs.clear();
    warnings.values().forEach(warning -> warning.delivery.cancel());
    warnings.clear();
  }
  void clearPending() {
    pendingConstructions.values().forEach(pending -> pending.preparation.cancel());
    pendingConstructions.clear();
  }
  void resetCursor() {
    assemblyCursor = 0;
  }
}
