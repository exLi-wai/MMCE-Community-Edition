package com.lw.modularmachine.machine.network;

import com.lw.modularmachine.machine.PortKind;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal immutable menu snapshot; quantities never live in prototype stacks.
 */
@ApiStatus.Internal
public record NetworkPortSnapshot(
    PortKind.Resource resource,
    boolean input,
    long configRevision,
    boolean active,
    boolean supported,
    List<Row> rows) {
  public NetworkPortSnapshot {
    Objects.requireNonNull(resource, "resource");
    rows = List.copyOf(rows);
    if (resource == PortKind.Resource.ENERGY || configRevision < 0 || rows.size() != (input ? 9 : 36)) {
      throw new IllegalArgumentException("Invalid network port snapshot");
    }
    for (var row : rows) {
      if (row.configured() != null && (!input || row.configured().kind() != resource)
          || row.buffered() != null && row.buffered().kind() != resource) {
        throw new IllegalArgumentException("Mixed network port resource types");
      }
    }
  }

  public record Row(NetworkPortResource configured, int target, NetworkPortResource buffered, long stored) {
    public Row {
      if (target < 0 || (configured == null) != (target == 0) || stored < 0 || (buffered == null) != (stored == 0)) {
        throw new IllegalArgumentException("Invalid network port row");
      }
    }
  }
}
