package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.machine.upgrade.UpgradeCatalog;
import com.lw.modularmachine.api.upgrade.UpgradeEntry;
import com.lw.modularmachine.machine.upgrade.UpgradeRestrictions;
import com.lw.modularmachine.machine.upgrade.UpgradeRestrictions.Installed;
import com.lw.modularmachine.machine.upgrade.UpgradeRestrictions.Result;
import com.lw.modularmachine.machine.upgrade.UpgradeCatalogService;
import com.lw.modularmachine.api.upgrade.UpgradeSnapshot;
import com.lw.modularmachine.machine.upgrade.UpgradeBusOwner;
import com.lw.modularmachine.machine.upgrade.UpgradeBusParts;
import com.lw.modularmachine.machine.recipe.PatternProductionService;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import com.lw.modularmachine.block.UpgradeBusBlock;
import com.lw.modularmachine.config.MachineConfig;

/**
 * Factory source discovery, restriction proofs and idle-thread snapshot
 * publication.
 */
@ApiStatus.Internal
public final class FactoryUpgradeSources {
  public interface Host {
    boolean persistentStateValid();
    boolean confirmed();
    boolean idle(String threadId);
    void markChanged();
    void invalidateSearch();
    void invalidateSearch(String threadId, long tick);
  }
  private final UpgradeBusOwner owner;
  private final FactoryThreadConfiguration configuration;
  private final Host host;

  public FactoryUpgradeSources(UpgradeBusOwner owner, FactoryThreadConfiguration configuration, Host host) {
    this.owner = owner;
    this.configuration = configuration;
    this.host = host;
  }

  public boolean valid() {
    return upgradeSourcesValid;
  }
  public boolean appliedTo(FactoryThreadState thread) {
    return pendingUpgradeSnapshot == null || thread.upgradeSnapshot().equals(pendingUpgradeSnapshot);
  }
  public boolean managed() {
    return upgradeSourcesManaged;
  }
  public void restoreManaged(boolean managed) {
    upgradeSourcesManaged = managed;
  }
  public void clearPublished() {
    appliedUpgradeSourceView = null;
    pendingUpgradeSnapshot = null;
  }
  public void configurationInstalled() {
    clearPublished();
    upgradeSourcesValid = true;
  }
  public void clearBuses() {
    upgradeBusSources.clear();
  }
  public void resetState() {
    clearBuses();
    clearPublished();
    upgradeSourceEpoch = 0;
    upgradeSourcesManaged = false;
    upgradeSourcesValid = true;
  }

  private final Map<BlockPos, UpgradeBusBlockEntity> upgradeBusSources = new HashMap<>();
  private StructureService.Registration upgradeDiscoveryRegistration;
  private List<BlockPos> upgradeDiscoveryPositions = List.of();
  private Set<BlockPos> upgradeDiscoveryMembership = Set.of();
  private long lastUpgradeDiscoveryTick = Long.MIN_VALUE;
  private List<Source> appliedUpgradeSourceView;
  private UpgradeSnapshot pendingUpgradeSnapshot;
  private long upgradeSourceEpoch;
  private boolean upgradeSourcesManaged;
  private boolean upgradeSourcesValid = true;
  private List<RestrictionSource> appliedRestrictionSources = List.of();
  private boolean upgradeRestrictionSourcesValid = true;
  private Result upgradeRestrictionStatus = UpgradeRestrictions.evaluate(List.of());

  private record RestrictionSource(
      BlockPos position,
      UpgradeBusBlockEntity identity,
      long revision,
      long definitionEpoch,
      ResourceLocation machine,
      boolean valid,
      List<Installed> installed) {
    private RestrictionSource {
      position = position.immutable();
      installed = List.copyOf(installed);
    }
  }

  public boolean updateUpgradeSnapshot(String threadId, long expectedRevision, UpgradeSnapshot snapshot) {
    if (!(owner.getLevel() instanceof ServerLevel server) || !server.getServer().isSameThread()) {
      throw new IllegalStateException("Factory upgrade snapshot updated outside server thread");
    }
    if (!host.persistentStateValid()
        || threadId == null
        || snapshot == null
        || expectedRevision < 0
        || !host.idle(threadId)) {
      return false;
    }
    var current = configuration.scheduler().channel(threadId);
    if (current == null || current.revision() != expectedRevision) {
      return false;
    }
    final FactoryThreadState next;
    try {
      next = current.withUpgradeSnapshot(snapshot);
    } catch (IllegalArgumentException invalid) {
      return false;
    }
    if (next == current) {
      return true;
    }
    configuration.replaceRuntime(configuration.scheduler().replace(next));
    PatternProductionService.invalidate(owner);
    host.invalidateSearch(threadId, server.getGameTime());
    host.markChanged();
    return true;
  }

  public boolean updateUpgradeCatalog(String threadId, long expectedRevision, List<UpgradeEntry> entries, long revision) {
    if (entries == null || revision < 0) {
      return false;
    }
    try {
      var catalog = UpgradeCatalog.compile(entries, revision);
      return updateUpgradeSnapshot(threadId, expectedRevision, catalog.snapshot());
    } catch (IllegalArgumentException | ArithmeticException invalid) {
      return false;
    }
  }

  /**
   * Validated server-side sources used to publish immutable thread snapshots.
   */
  public record Source(BlockPos position, UpgradeCatalog catalog) {
    public Source {
      position = position.immutable();
      Objects.requireNonNull(catalog, "catalog");
    }
  }

  public List<Source> sources() {
    requireUpgradeThread();
    upgradeBusSources.entrySet().removeIf(entry -> !entry.getValue().isBoundTo(owner));

    return upgradeBusSources.entrySet().stream().sorted(Comparator.comparingLong(entry -> entry.getKey().asLong()))
        .map(entry -> new Source(entry.getKey(), entry.getValue().upgradeCatalog(owner))).toList();
  }

  public boolean publishUpgradeBus(UpgradeBusBlockEntity bus) {
    requireUpgradeThread();
    if (!bus.isBoundTo(owner)) {
      return false;
    }
    var position = bus.getBlockPos().immutable();
    if (!upgradeBusSources.containsKey(position) && upgradeBusSources.size() >= UpgradeCatalog.MAX_SLOTS) {
      return false;
    }
    upgradeBusSources.put(position, bus);

    return true;
  }

  public void withdrawUpgradeBus(UpgradeBusBlockEntity bus) {
    requireUpgradeThread();
    upgradeBusSources.remove(bus.getBlockPos(), bus);
  }

  private void requireUpgradeThread() {
    if (!(owner.getLevel() instanceof ServerLevel server)) {
      throw new IllegalStateException("Factory upgrade sources require a server level");
    }
    ServerThreadGuard.require(server, "Factory upgrade sources accessed");
  }

  public void clearDiscovery() {
    appliedRestrictionSources = List.of();
    upgradeRestrictionSourcesValid = true;
    upgradeRestrictionStatus = UpgradeRestrictions.evaluate(List.of());
    upgradeDiscoveryRegistration = null;
    upgradeDiscoveryPositions = List.of();
    upgradeDiscoveryMembership = Set.of();
    lastUpgradeDiscoveryTick = Long.MIN_VALUE;
  }

  public void refreshBindings(ServerLevel server) {
    if (!owner.canCommitStructure()) {
      clearDiscovery();
      return;
    }
    prepareUpgradeDiscovery();
    long now = server.getGameTime();
    if (lastUpgradeDiscoveryTick != Long.MIN_VALUE
        && now >= lastUpgradeDiscoveryTick
        && now - lastUpgradeDiscoveryTick < 20) {
      return;
    }
    lastUpgradeDiscoveryTick = now;
    for (var position : upgradeDiscoveryPositions) {
      if (server.hasChunkAt(position) && server.getBlockEntity(position) instanceof UpgradeBusBlockEntity bus) {
        bus.bindOwner(owner);
      }
    }
  }

  public boolean containsUpgradeBusPart(BlockPos position) {
    requireUpgradeThread();
    if (!owner.canCommitStructure()) {
      return false;
    }
    prepareUpgradeDiscovery();

    return upgradeDiscoveryMembership.contains(position);
  }

  private void prepareUpgradeDiscovery() {
    var registration = owner.structureRegistration();
    if (upgradeDiscoveryRegistration != registration) {
      var parts = UpgradeBusParts.compile(registration);
      upgradeDiscoveryRegistration = registration;
      upgradeDiscoveryPositions = parts.positions();
      upgradeDiscoveryMembership = parts.membership();
      lastUpgradeDiscoveryTick = Long.MIN_VALUE;
    }
  }

  public List<BlockPos> upgradeBusPositions() {
    requireUpgradeThread();
    var registration = owner.structureRegistration();
    if (registration == null || !registration.currentForObservation((ServerLevel) owner.getLevel())) {
      return List.of();
    }
    prepareUpgradeDiscovery();

    return upgradeDiscoveryPositions;
  }

  public boolean mayHaveTickWork() {
    if (!UpgradeCatalogService.snapshot().hasTickWork()) {
      return false;
    }
    var level = (ServerLevel) owner.getLevel();
    for (var position : upgradeBusPositions()) {
      if (!level.hasChunkAt(position)) {
        return true;
      }
      if (!(level.getBlockState(position).getBlock() instanceof UpgradeBusBlock)) {
        continue;
      }
      if (!(level.getBlockEntity(position) instanceof UpgradeBusBlockEntity bus) || !bus.tickCostsReady(owner)) {
        return true;
      }
      if (!bus.tickCostSlots(owner).isEmpty() || !bus.tickDataSubscriptions(owner).isEmpty()) {
        return true;
      }
      if (MachineConfig.CONSERVATIVE_COMMIT_CHECK.get()) {
        try {
          if (!bus.auditTickCosts(owner)
              || !bus.tickCostSlots(owner).isEmpty()
              || !bus.tickDataSubscriptions(owner).isEmpty()) {
            return true;
          }
        } catch (RuntimeException unavailable) {
          // The regular cost preparer diagnoses a stale source and holds the paid batch.
          return true;
        }
      }
    }
    return false;
  }

  public List<UpgradeBusBlockEntity> upgradeCallbackBuses() {
    requireUpgradeThread();
    if (!owner.canCommitStructure()) {
      return List.of();
    }
    prepareUpgradeDiscovery();
    var server = (ServerLevel) owner.getLevel();
    var buses = new ArrayList<UpgradeBusBlockEntity>();
    for (var position : upgradeDiscoveryPositions) {
      if (server.hasChunkAt(position)
          && server.getBlockEntity(position) instanceof UpgradeBusBlockEntity bus
          && bus.hasOwnerBinding(owner)) {
        buses.add(bus);
      }
    }

    return List.copyOf(buses);
  }

  public void refreshSnapshot() {
    if (!host.confirmed() || configuration.scheduler().channels().isEmpty()) {
      return;
    }
    if (!refreshUpgradeRestrictions()) {
      upgradeSourcesManaged = true;
      upgradeSourcesValid = false;
      appliedUpgradeSourceView = null;
      pendingUpgradeSnapshot = null;
      host.invalidateSearch();
      var reason = upgradeRestrictionStatus
          .valid() ? FactoryThreadState.Reason.CHECK_ERROR : FactoryThreadState.Reason.UPGRADE_CONFLICT;
      for (var thread : List.copyOf(configuration.scheduler().channels())) {
        if (host.idle(thread.threadId())) {
          configuration.updateThreadState(thread.threadId(), FactoryThreadState.State.WAITING, reason);
        }
      }
      return;
    }
    var sources = sources();
    if (sources.isEmpty() && !upgradeSourcesManaged) {
      upgradeSourcesValid = true;
      return;
    }
    if (!sources.equals(appliedUpgradeSourceView)) {
      PatternProductionService.invalidate(owner);
      upgradeSourcesManaged = true;
      try {
        long maximumRevision = configuration.scheduler().channels().stream()
            .mapToLong(FactoryThreadState::upgradeRevision).max().orElse(0L);
        long nextEpoch = Math.incrementExact(Math.max(upgradeSourceEpoch, maximumRevision));
        var composed = sources.isEmpty() ? UpgradeSnapshot.identity(nextEpoch) : UpgradeSnapshot
            .compose(sources.stream().map(source -> source.catalog().snapshot()).toList());
        pendingUpgradeSnapshot = composed.withRevision(nextEpoch);
        upgradeSourceEpoch = nextEpoch;
        upgradeSourcesValid = true;
      } catch (IllegalArgumentException | ArithmeticException invalid) {
        pendingUpgradeSnapshot = null;
        upgradeSourcesValid = false;
        host.invalidateSearch();
      }
      appliedUpgradeSourceView = List.copyOf(sources);
      host.markChanged();
    }
    if (!upgradeSourcesValid) {
      for (var thread : List.copyOf(configuration.scheduler().channels())) {
        if (host.idle(thread.threadId())) {
          configuration.updateThreadState(
              thread.threadId(),
              FactoryThreadState.State.WAITING,
              FactoryThreadState.Reason.CHECK_ERROR);
        }
      }
      return;
    }
    if (pendingUpgradeSnapshot == null) {
      return;
    }
    boolean complete = true;
    for (var thread : List.copyOf(configuration.scheduler().channels())) {
      if (thread.upgradeSnapshot().equals(pendingUpgradeSnapshot)) {
        continue;
      }
      complete = false;
      if (host.idle(thread.threadId())) {
        updateUpgradeSnapshot(thread.threadId(), thread.revision(), pendingUpgradeSnapshot);
      }
    }
    if (complete) {
      pendingUpgradeSnapshot = null;
    }
  }

  private List<RestrictionSource> captureRestrictionSources(boolean refresh) {
    var result = new ArrayList<RestrictionSource>();
    for (var bus : upgradeCallbackBuses()) {
      if (refresh) {
        bus.refreshUpgradeDefinitions();
      }
      boolean valid = bus.upgradeRestrictionSourcesValid(owner);
      result.add(
          new RestrictionSource(
              bus.getBlockPos(),
              bus,
              bus.upgradeRevision(),
              UpgradeCatalogService.snapshot().epoch(),
              owner.machineId(),
              valid,
              valid ? bus.upgradeRestrictions(owner) : List.of()));
    }

    return List.copyOf(result);
  }

  private boolean refreshUpgradeRestrictions() {
    var sources = captureRestrictionSources(true);
    if (!sources.equals(appliedRestrictionSources)) {
      upgradeRestrictionSourcesValid = sources.stream().allMatch(RestrictionSource::valid);
      try {
        upgradeRestrictionStatus = upgradeRestrictionSourcesValid ? UpgradeRestrictions
            .evaluate(sources.stream().flatMap(source -> source.installed().stream()).toList()) : UpgradeRestrictions
                .evaluate(List.of());
      } catch (IllegalArgumentException | ArithmeticException inconsistent) {
        upgradeRestrictionSourcesValid = false;
        upgradeRestrictionStatus = UpgradeRestrictions.evaluate(List.of());
      }
      appliedRestrictionSources = sources;
      host.markChanged();
    }

    return upgradeRestrictionSourcesValid && upgradeRestrictionStatus.valid();
  }

  public Result upgradeRestrictionStatus() {
    requireUpgradeThread();

    return upgradeRestrictionStatus;
  }

  /** Passive final fence: this read never runs a dynamic upgrade callback. */
  public boolean upgradeRestrictionsCurrent() {
    requireUpgradeThread();

    return owner.canCommitStructure()
        && upgradeRestrictionSourcesValid
        && upgradeRestrictionStatus.valid()
        && captureRestrictionSources(false).equals(appliedRestrictionSources);
  }

}
