package com.lw.modularmachine.machine.controller;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal ownership persistence, independent of production and script data.
 */
@ApiStatus.Internal
public final class ControllerOwnershipState {
  private static final String NBT_KEY = "owner";
  private static final ControllerOwnershipState EMPTY = new ControllerOwnershipState(null, null);
  private final UUID ownerId;
  private final Tag unsupportedData;

  private ControllerOwnershipState(UUID ownerId, Tag unsupportedData) {
    this.ownerId = ownerId;
    this.unsupportedData = unsupportedData;
  }

  public static ControllerOwnershipState empty() {
    return EMPTY;
  }

  public static ControllerOwnershipState ownedBy(UUID ownerId) {
    return new ControllerOwnershipState(Objects.requireNonNull(ownerId, "ownerId"), null);
  }

  public static ControllerOwnershipState load(CompoundTag root) {
    if (!root.contains(NBT_KEY)) {
      return empty();
    }
    if (root.hasUUID(NBT_KEY)) {
      return ownedBy(root.getUUID(NBT_KEY));
    }

    return new ControllerOwnershipState(null, root.get(NBT_KEY).copy());
  }

  public boolean valid() {
    return unsupportedData == null;
  }

  public Optional<UUID> ownerId() {
    return Optional.ofNullable(ownerId);
  }

  public boolean permits(UUID playerId, boolean creative, boolean securityEnabled) {
    return creative || !securityEnabled || valid() && (ownerId == null || ownerId.equals(playerId));
  }

  public void save(CompoundTag root) {
    root.remove(NBT_KEY);
    if (unsupportedData != null) {
      root.put(NBT_KEY, unsupportedData.copy());
    } else if (ownerId != null) {
      root.putUUID(NBT_KEY, ownerId);
    }
  }

  public CompoundTag clientTag() {
    var root = new CompoundTag();
    if (valid()) {
      save(root);
    } else {
      root.putBoolean(NBT_KEY, false);
    }

    return root;
  }
}
