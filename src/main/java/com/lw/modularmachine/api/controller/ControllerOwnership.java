package com.lw.modularmachine.api.controller;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable player ownership; names and online status are informational. */
public record ControllerOwnership(
    boolean valid,
    Optional<UUID> ownerId,
    Optional<String> ownerName,
    boolean ownerOnline) {
  public ControllerOwnership {
    Objects.requireNonNull(ownerId, "ownerId");
    Objects.requireNonNull(ownerName, "ownerName");
    if (!valid && ownerId.isPresent()
        || ownerId.isEmpty() && (ownerName.isPresent() || ownerOnline)
        || ownerName.isPresent() && (ownerName.get().isBlank() || ownerName.get().length() > 16)) {
      throw new IllegalArgumentException("Invalid controller ownership snapshot");
    }
  }

  public String getOwnerUUIDString() {
    return ownerId.map(UUID::toString).orElse(null);
  }

  public String getOwnerName() {
    return ownerName.orElse(null);
  }
}
