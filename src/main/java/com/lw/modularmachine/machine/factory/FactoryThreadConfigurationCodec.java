package com.lw.modularmachine.machine.factory;

import java.util.ArrayList;
import java.util.HashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.ApiStatus;

/**
 * Existing factory7 scheduler fields; run/identity state remains in its owning
 * codec.
 */
@ApiStatus.Internal
final class FactoryThreadConfigurationCodec {
  private FactoryThreadConfigurationCodec() {
  }

  record Loaded(FactoryScheduler scheduler, boolean initialized, long revision) {
  }

  static boolean hasFields(CompoundTag tag) {
    return tag.contains("threads_initialized", Tag.TAG_BYTE)
        && tag.getByte("threads_initialized") >= 0
        && tag.getByte("threads_initialized") <= 1
        && tag.contains("configuration_revision", Tag.TAG_LONG)
        && tag.getLong("configuration_revision") >= 0
        && tag.contains("scheduler_cursor", Tag.TAG_INT)
        && tag.contains("scheduler_round", Tag.TAG_LIST)
        && tag.contains("scheduler_waiting", Tag.TAG_COMPOUND)
        && tag.contains("threads", Tag.TAG_LIST);
  }

  static void write(CompoundTag tag, FactoryScheduler scheduler, boolean threadsInitialized, long configurationRevision) {
    tag.putBoolean("threads_initialized", threadsInitialized);
    tag.putLong("configuration_revision", configurationRevision);
    tag.putInt("scheduler_cursor", scheduler.cursor());
    var round = new ListTag();
    for (var id : scheduler.roundOrder()) {
      round.add(StringTag.valueOf(id));
    }
    tag.put("scheduler_round", round);
    var waiting = new CompoundTag();
    scheduler.waitingAges().forEach(waiting::putInt);
    tag.put("scheduler_waiting", waiting);
    tag.put("threads", FactoryThreadStateCodec.write(scheduler.channels()));
  }

  static Loaded read(CompoundTag tag) {
    if (!hasFields(tag)) {
      throw new IllegalArgumentException("Invalid factory thread configuration fields");
    }
    if (!(tag.get("threads") instanceof ListTag threadsTag)
        || !threadsTag.isEmpty() && threadsTag.getElementType() != Tag.TAG_COMPOUND) {
      throw new IllegalArgumentException("Invalid factory thread list type");
    }
    var loaded = FactoryThreadStateCodec.read(threadsTag);
    int cursor = tag.getInt("scheduler_cursor");
    var roundTag = (ListTag) tag.get("scheduler_round");
    if (roundTag.size() > FactoryThreadStateCodec.MAX_THREADS
        || !roundTag.isEmpty() && roundTag.getElementType() != Tag.TAG_STRING) {
      throw new IllegalArgumentException("Invalid factory round order");
    }
    var round = new ArrayList<String>(roundTag.size());
    for (int i = 0; i < roundTag.size(); i++) {
      round.add(roundTag.getString(i));
    }
    var waitingTag = tag.getCompound("scheduler_waiting");
    if (waitingTag.size() > FactoryThreadStateCodec.MAX_THREADS) {
      throw new IllegalArgumentException("Too many factory waiting entries");
    }
    var waiting = new HashMap<String, Integer>();
    for (var key : waitingTag.getAllKeys()) {
      if (!waitingTag.contains(key, Tag.TAG_INT)) {
        throw new IllegalArgumentException("Invalid factory waiting age type");
      }
      waiting.put(key, waitingTag.getInt(key));
    }
    return new Loaded(
        new FactoryScheduler(loaded, cursor, round, waiting),
        tag.getBoolean("threads_initialized"),
        tag.getLong("configuration_revision"));
  }
}
