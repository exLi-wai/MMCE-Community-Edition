package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.machine.recipe.FrozenItemRun;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Strict NBT codec for a parallel factory run set. */
public final class FactoryRunSetCodec {
  private FactoryRunSetCodec() {
  }

  public static ListTag write(FactoryRunSet set) {
    if (set == null) {
      throw new IllegalArgumentException("Missing factory run set");
    }
    var result = new ListTag();
    for (var run : set.runs()) {
      var entry = new CompoundTag();
      entry.put("run", run.save());
      result.add(entry);
    }
    return result;
  }

  public static FactoryRunSet read(ListTag list, HolderLookup.Provider registries) {
    if (list == null || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) {
      throw new IllegalArgumentException("Invalid factory run set list");
    }
    var ids = new HashSet<UUID>();
    var runs = new ArrayList<FrozenItemRun>(list.size());
    for (int i = 0; i < list.size(); i++) {
      var entry = list.getCompound(i);
      if (!entry.contains("run", Tag.TAG_COMPOUND)) {
        throw new IllegalArgumentException("Invalid factory run set entry");
      }
      var run = FrozenItemRun.read(entry.getCompound("run"), registries);
      if (!ids.add(run.runId())) {
        throw new IllegalArgumentException("Duplicate factory run UUID");
      }
      runs.add(run);
    }
    return FactoryRunSet.ofRuns(runs);
  }
}
