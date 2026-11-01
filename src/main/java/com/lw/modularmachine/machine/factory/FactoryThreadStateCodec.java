package com.lw.modularmachine.machine.factory;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/** Bounded NBT codec for persistent factory channel intent. */
public final class FactoryThreadStateCodec {
  public static final int MAX_THREADS = 256;

  private FactoryThreadStateCodec() {
  }

  public static ListTag write(List<FactoryThreadState> threads) {
    if (threads == null || threads.size() > MAX_THREADS) {
      throw new IllegalArgumentException("Too many factory threads");
    }
    var result = new ListTag();
    for (FactoryThreadState thread : threads) {
      if (thread == null) {
        throw new IllegalArgumentException("Null factory thread");
      }
      var tag = new CompoundTag();
      tag.putString("id", thread.threadId());
      tag.putString("machine", thread.machineId().toString());
      if (thread.lockedRecipe() != null) {
        tag.putString("locked_recipe", thread.lockedRecipe().toString());
      }
      tag.putBoolean("enabled", thread.enabled());
      tag.putInt("priority", thread.priority());
      tag.putInt("parallel_limit", thread.parallelLimit());
      tag.putLong("upgrade_revision", thread.upgradeRevision());
      tag.put("upgrade_snapshot", FactoryUpgradeSnapshotCodec.write(thread.upgradeSnapshot()));
      tag.putString("state", thread.state().name());
      tag.putString("reason", thread.reason().name());
      tag.putLong("revision", thread.revision());
      result.add(tag);
    }
    return result;
  }

  public static List<FactoryThreadState> read(ListTag tags) {
    if (tags == null || tags.size() > MAX_THREADS) {
      throw new IllegalArgumentException("Too many factory threads");
    }
    var result = new ArrayList<FactoryThreadState>(tags.size());
    for (int i = 0; i < tags.size(); i++) {
      if (!(tags.get(i) instanceof CompoundTag tag)) {
        throw new IllegalArgumentException("Invalid factory thread entry");
      }
      if (!tag.contains("id", Tag.TAG_STRING)
          || !tag.contains("machine", Tag.TAG_STRING)
          || !tag.contains("enabled", Tag.TAG_BYTE)
          || !tag.contains("priority", Tag.TAG_INT)
          || !tag.contains("parallel_limit", Tag.TAG_INT)
          || !tag.contains("upgrade_revision", Tag.TAG_LONG)
          || !tag.contains("upgrade_snapshot", Tag.TAG_COMPOUND)
          || !tag.contains("state", Tag.TAG_STRING)
          || !tag.contains("reason", Tag.TAG_STRING)
          || !tag.contains("revision", Tag.TAG_LONG)) {
        throw new IllegalArgumentException("Incomplete factory thread entry");
      }
      var lockedRecipe = tag.contains("locked_recipe", Tag.TAG_STRING) ? ResourceLocation
          .parse(tag.getString("locked_recipe")) : null;
      var snapshot = FactoryUpgradeSnapshotCodec.read(tag.getCompound("upgrade_snapshot"));
      var state = new FactoryThreadState(
          tag.getString("id"),
          ResourceLocation.parse(tag.getString("machine")),
          lockedRecipe,
          tag.getBoolean("enabled"),
          tag.getInt("priority"),
          tag.getInt("parallel_limit"),
          tag.getLong("upgrade_revision"),
          FactoryThreadState.State.valueOf(tag.getString("state")),
          FactoryThreadState.Reason.valueOf(tag.getString("reason")),
          tag.getLong("revision"),
          snapshot);
      if (snapshot.revision() != state.upgradeRevision() || snapshot.parallelLimit() != state.parallelLimit()) {
        throw new IllegalArgumentException("Factory upgrade snapshot does not match thread state");
      }
      result.add(state);
    }
    return List.copyOf(result);
  }
}
