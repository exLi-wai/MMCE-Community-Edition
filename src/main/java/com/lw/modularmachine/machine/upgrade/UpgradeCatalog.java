package com.lw.modularmachine.machine.upgrade;

import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.api.upgrade.UpgradeSnapshot;
import com.lw.modularmachine.api.upgrade.UpgradeEntry;
import java.util.List;
import java.util.Objects;

/** Bounded immutable upgrade-slot view and its composed effect snapshot. */
@ApiStatus.Internal
public record UpgradeCatalog(
    List<UpgradeEntry> entries,
    UpgradeSnapshot snapshot,
    List<UpgradeRestrictions.Installed> installed) {
  public static final int MAX_SLOTS = 256;

  public UpgradeCatalog(List<UpgradeEntry> entries, UpgradeSnapshot snapshot) {
    this(entries, snapshot, List.of());
  }

  public UpgradeCatalog {
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

  public static UpgradeCatalog compile(List<UpgradeEntry> entries, long revision) {
    return compile(entries, revision, List.of());
  }

  public static UpgradeCatalog compile(List<UpgradeEntry> entries, long revision, List<UpgradeRestrictions.Installed> installed) {
    Objects.requireNonNull(entries, "entries");
    if (revision < 0 || entries.size() > MAX_SLOTS) {
      throw new IllegalArgumentException("Factory upgrade slots outside supported range");
    }
    if (entries.isEmpty()) {
      return new UpgradeCatalog(entries, UpgradeSnapshot.identity(revision), installed);
    }
    var snapshots = entries.stream().map(entry -> Objects.requireNonNull(entry, "entry").snapshot(revision)).toList();
    return new UpgradeCatalog(entries, UpgradeSnapshot.compose(snapshots), installed);
  }
}
