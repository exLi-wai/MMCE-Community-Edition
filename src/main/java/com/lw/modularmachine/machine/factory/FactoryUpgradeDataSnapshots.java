package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.api.upgrade.FactoryUpgradeItemData;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.ApiStatus;

/**
 * Shared upgrade-domain NBT limits; validation precedes recursive defensive
 * copy.
 */
@ApiStatus.Internal
public final class FactoryUpgradeDataSnapshots {
  private record Visit(Tag tag, int depth, boolean exit) {
  }

  private FactoryUpgradeDataSnapshots() {
  }

  public static CompoundTag copy(CompoundTag data) {
    return copy(
        data,
        FactoryUpgradeItemData.MAX_BYTES,
        FactoryUpgradeItemData.MAX_TAGS,
        FactoryUpgradeItemData.MAX_DEPTH);
  }

  public static CompoundTag copy(CompoundTag data, int maxBytes, int maxTags, int maxDepth) {
    validate(data, maxBytes, maxTags, maxDepth);

    return data.copy();
  }

  public static void validate(CompoundTag data, int maxBytes, int maxTags, int maxDepth) {
    Objects.requireNonNull(data, "data");
    var pending = new ArrayDeque<Visit>();
    var path = Collections.newSetFromMap(new IdentityHashMap<Tag, Boolean>());
    pending.push(new Visit(data, 0, false));
    int tags = 1;
    while (!pending.isEmpty()) {
      var visit = pending.pop();
      if (visit.exit()) {
        path.remove(visit.tag());
        continue;
      }
      if (visit.depth() > maxDepth) {
        throw new IllegalArgumentException("Upgrade item data exceeds depth limit");
      }
      var tag = visit.tag();
      if (tag instanceof CompoundTag compound) {
        if (!path.add(compound)) {
          throw new IllegalArgumentException("Cyclic upgrade item data");
        }
        tags = Math.addExact(tags, compound.size());
        if (tags > maxTags) {
          throw new IllegalArgumentException("Too many upgrade data tags");
        }
        pending.push(new Visit(tag, visit.depth(), true));
        for (String key : compound.getAllKeys()) {
          if (key.length() > maxBytes) {
            throw new IllegalArgumentException("Upgrade data key exceeds size limit");
          }
          pending.push(new Visit(compound.get(key), visit.depth() + 1, false));
        }
      } else if (tag instanceof ListTag list) {
        if (!path.add(list)) {
          throw new IllegalArgumentException("Cyclic upgrade item data");
        }
        tags = Math.addExact(tags, list.size());
        if (tags > maxTags) {
          throw new IllegalArgumentException("Too many upgrade data tags");
        }
        pending.push(new Visit(tag, visit.depth(), true));
        for (Tag child : list) {
          pending.push(new Visit(child, visit.depth() + 1, false));
        }
      } else if (tag instanceof StringTag string && string.getAsString().length() > maxBytes
          || tag instanceof ByteArrayTag bytes && bytes.getAsByteArray().length > maxBytes
          || tag instanceof IntArrayTag integers && (long) integers.getAsIntArray().length * Integer.BYTES > maxBytes
          || tag instanceof LongArrayTag longs && (long) longs.getAsLongArray().length * Long.BYTES > maxBytes) {
        throw new IllegalArgumentException("Upgrade item data value exceeds size limit");
      }
    }
    try (var output = new DataOutputStream(new OutputStream() {
      private int bytes;

      private void count(int length) throws IOException {
        if (length > maxBytes - bytes) {
          throw new IOException("Upgrade item data exceeds byte limit");
        }
        bytes += length;
      }

      @Override
      public void write(int value) throws IOException {
        count(1);
      }

      @Override
      public void write(byte[] values, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, values.length);
        count(length);
      }
    })) {
      NbtIo.write(data, output);
    } catch (IOException invalid) {
      throw new IllegalArgumentException("Invalid or oversized upgrade item data", invalid);
    }
  }
}
