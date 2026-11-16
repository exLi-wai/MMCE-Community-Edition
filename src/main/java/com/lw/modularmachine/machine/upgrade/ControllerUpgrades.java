package com.lw.modularmachine.machine.upgrade;

import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.api.upgrade.UpgradeSnapshot;
import com.lw.modularmachine.block.UpgradeBusBlock;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import com.lw.modularmachine.machine.recipe.PatternProductionService;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Ordinary-controller aggregation over the same physical sources and arithmetic
 * used by factories.
 */
@ApiStatus.Internal
public final class ControllerUpgrades {
  private final UpgradeBusOwner owner;
  private StructureService.Registration registration;
  private UpgradeBusParts parts = new UpgradeBusParts(List.of(), Set.of());
  private List<Source> applied;
  private long epoch = -1;
  private long revision;
  private long discoveryTick = Long.MIN_VALUE;
  private boolean ready;
  private UpgradeSnapshot snapshot = UpgradeSnapshot.identity(0);
  private UpgradeRestrictions.Result restrictions = UpgradeRestrictions.evaluate(List.of());

  private record Source(
      BlockPos position,
      BlockState state,
      UpgradeBusBlockEntity bus,
      long revision,
      UpgradeCatalog catalog,
      boolean bound,
      boolean valid,
      boolean rulesValid,
      List<UpgradeRestrictions.Installed> rules) {
  }

  public ControllerUpgrades(UpgradeBusOwner owner) {
    this.owner = owner;
  }

  private ServerLevel server() {
    if (!(owner.getLevel() instanceof ServerLevel server)) {
      throw new IllegalStateException("Controller upgrades require a server level");
    }
    ServerThreadGuard.require(server, "Controller upgrades accessed");

    return server;
  }

  public void invalidate() {
    registration = null;
    parts = new UpgradeBusParts(List.of(), Set.of());
    discoveryTick = Long.MIN_VALUE;
    applied = null;
    ready = false;
  }

  public void withdraw() {
    server();
    boolean wasReady = ready;
    applied = null;
    ready = false;
    if (wasReady) {
      PatternProductionService.invalidate(owner);
    }
  }

  private void prepareParts() {
    var current = owner.structureRegistration();
    if (registration != current) {
      registration = current;
      parts = current == null ? new UpgradeBusParts(List.of(), Set.of()) : UpgradeBusParts.compile(current);
      discoveryTick = Long.MIN_VALUE;
      applied = null;
      ready = false;
    }
  }

  public boolean contains(BlockPos position) {
    server();
    if (!owner.canCommitStructure()) {
      return false;
    }
    prepareParts();

    return parts.membership().contains(position);
  }

  public List<UpgradeBusBlockEntity> buses() {
    var server = server();
    if (!owner.canCommitStructure()) {
      return List.of();
    }
    prepareParts();
    var buses = new ArrayList<UpgradeBusBlockEntity>();
    for (var position : parts.positions()) {
      if (server.hasChunkAt(position)
          && server.getBlockState(position).getBlock() instanceof UpgradeBusBlock
          && server.getBlockEntity(position) instanceof UpgradeBusBlockEntity bus
          && bus.hasOwnerBinding(owner)) {
        buses.add(bus);
      }
    }

    return List.copyOf(buses);
  }

  public List<BlockPos> positions() {
    server();
    prepareParts();

    return parts.positions();
  }

  public void refresh() {
    var server = server();
    if (UpgradeLifecyclePublisher.isDispatching(owner)) {
      return;
    }
    var previousSnapshot = snapshot;
    boolean wasReady = ready;
    try {
      refreshSources(server);
    } finally {
      if (wasReady != ready || previousSnapshot != snapshot) {
        PatternProductionService.invalidate(owner);
      }
    }
  }

  private void refreshSources(ServerLevel server) {
    if (!owner.canCommitStructure()) {
      invalidate();
      return;
    }
    prepareParts();
    long now = server.getGameTime();
    boolean discover = discoveryTick == Long.MIN_VALUE || now < discoveryTick || now - discoveryTick >= 20;
    if (discover) {
      discoveryTick = now;
    }
    for (var position : parts.positions()) {
      if (server.hasChunkAt(position)
          && server.getBlockState(position).getBlock() instanceof UpgradeBusBlock
          && server.getBlockEntity(position) instanceof UpgradeBusBlockEntity bus) {
        if (discover) {
          bus.bindOwner(owner);
        }
        bus.refreshUpgradeDefinitions();
      }
    }
    long nextEpoch = UpgradeCatalogService.snapshot().epoch();
    var sources = capture();
    if (epoch == nextEpoch && sources.equals(applied)) {
      return;
    }
    applied = sources;
    epoch = nextEpoch;
    ready = false;
    restrictions = UpgradeRestrictions.evaluate(List.of());
    try {
      if (sources.stream().anyMatch(source -> !source.bound() || !source.rulesValid())) {
        return;
      }
      restrictions = UpgradeRestrictions.evaluate(sources.stream().flatMap(source -> source.rules().stream()).toList());
      if (!restrictions.valid() || sources.stream().anyMatch(source -> !source.valid()) || revision == Long.MAX_VALUE) {
        return;
      }
      var effects = sources.stream().filter(source -> source.catalog() != null)
          .map(source -> source.catalog().snapshot()).toList();
      var composed = effects.isEmpty() ? UpgradeSnapshot.identity(0) : UpgradeSnapshot.compose(effects);
      snapshot = composed.withRevision(++revision);
      ready = true;
    } catch (IllegalArgumentException | ArithmeticException invalid) {
      ready = false;
    }
  }

  private List<Source> capture() {
    var server = server();
    var sources = new ArrayList<Source>();
    for (var position : parts.positions()) {
      if (!server.hasChunkAt(position)) {
        sources.add(new Source(position, null, null, 0, null, false, false, false, List.of()));
        continue;
      }
      var state = server.getBlockState(position);
      if (!(state.getBlock() instanceof UpgradeBusBlock)) {
        sources.add(new Source(position, state, null, 0, null, true, true, true, List.of()));
        continue;
      }
      if (!(server.getBlockEntity(position) instanceof UpgradeBusBlockEntity bus)) {
        sources.add(new Source(position, state, null, 0, null, false, false, false, List.of()));
        continue;
      }
      boolean bound = bus.hasOwnerBinding(owner);
      boolean rulesValid = bus.upgradeRestrictionSourcesValid(owner);
      sources.add(
          new Source(
              position,
              state,
              bus,
              bus.upgradeRevision(),
              bus.upgradeCatalog(owner),
              bound,
              bus.upgradeCatalogValid(owner),
              rulesValid,
              rulesValid ? bus.upgradeRestrictions(owner) : List.of()));
    }

    return List.copyOf(sources);
  }

  public boolean current() {
    var server = server();
    if (!ready
        || !owner.canCommitStructure()
        || registration != owner.structureRegistration()
        || epoch != UpgradeCatalogService.snapshot().epoch()) {
      return false;
    }
    for (var source : applied) {
      if (!server.hasChunkAt(source.position()) || server.getBlockState(source.position()) != source.state()) {
        return false;
      }
      var bus = source.bus();
      if (bus != null
          && (server.getBlockEntity(source.position()) != bus
              || bus.upgradeRevision() != source.revision()
              || bus.upgradeCatalog(owner) != source.catalog()
              || !bus.upgradeCatalogValid(owner)
              || !bus.upgradeRestrictionSourcesValid(owner))) {
        return false;
      }
    }

    return true;
  }

  public boolean current(UpgradeSnapshot expected) {
    return snapshot == expected && current();
  }

  public UpgradeSnapshot snapshot() {
    server();

    return snapshot;
  }

  public UpgradeRestrictions.Result restrictions() {
    server();

    return restrictions;
  }
}
