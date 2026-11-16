package com.lw.modularmachine.integration.ae2;

import appeng.api.networking.IManagedGridNode;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.ApiStatus;

/**
 * Shared node/owner serialization; connections retain their lifecycle state.
 */
@ApiStatus.Internal
final class Ae2NodePersistence {
  static final String NODE_TAG = "node";
  static final String OWNER_TAG = "owner_id";

  private Ae2NodePersistence() {
  }

  static void save(CompoundTag tag, IManagedGridNode node, CompoundTag retained, int owningPlayerId) {
    if (node != null) {
      node.saveToNBT(tag);
    } else if (retained.contains(NODE_TAG, Tag.TAG_COMPOUND)) {
      tag.put(NODE_TAG, retained.getCompound(NODE_TAG).copy());
    }
    int owner = node != null && node.getNode() != null ? node.getNode().getOwningPlayerId() : owningPlayerId;
    if (owner >= 0) {
      tag.putInt(OWNER_TAG, owner);
    }
  }
}
