package com.lw.modularmachine.machine.factory;

import java.util.List;
import java.util.Objects;

/** Bounded immutable upgrade-slot view and its composed effect snapshot. */
public record FactoryUpgradeCatalog(
    List<FactoryUpgradeEntry> entries,
    FactoryUpgradeSnapshot snapshot,
    List<FactoryUpgradeRestrictions.Installed> installed) {
  public static final int MAX_SLOTS = 256;

  public FactoryUpgradeCatalog(List<FactoryUpgradeEntry> entries, FactoryUpgradeSnapshot snapshot) {
    this(entries, snapshot, List.of());
  }

  public FactoryUpgradeCatalog {
    Objects.requireNonNull(entries, "entries");
    Objects.requireNonNull(snapshot, "snapshot");
    entries = List.copyOf(entries);
    installed = List.copyOf(installed);
    if (installed.size() > MAX_SLOTS) {
      throw new IllegalArgumentException("Too many upgrade restriction sources");
    }
    if (entries.size() > MAX_SLOTS || entries.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("Invalid factory upgrade catalog");
    }
  }

  public static FactoryUpgradeCatalog compile(List<FactoryUpgradeEntry> entries, long revision) {
    return compile(entries, revision, List.of());
  }

  public static FactoryUpgradeCatalog compile(List<FactoryUpgradeEntry> entries, long revision, List<FactoryUpgradeRestrictions.Installed> installed) {
    Objects.requireNonNull(entries, "entries");
    if (revision < 0 || entries.size() > MAX_SLOTS) {
      throw new IllegalArgumentException("Factory upgrade slots outside supported range");
    }
    if (entries.isEmpty()) {
      return new FactoryUpgradeCatalog(entries, FactoryUpgradeSnapshot.identity(revision), installed);
    }
    var snapshots = entries.stream().map(entry -> Objects.requireNonNull(entry, "entry").snapshot(revision)).toList();
    return new FactoryUpgradeCatalog(entries, FactoryUpgradeSnapshot.compose(snapshots), installed);
  }
}
