package com.lw.modularmachine.api.client.preview;

import java.util.Objects;
import net.minecraft.nbt.CompoundTag;

/**
 * Immutable visual data shared by alternatives; every consumer receives a copy.
 */
public final class PreviewBlockData {
  public static final PreviewBlockData EMPTY = new PreviewBlockData(new CompoundTag());
  private final CompoundTag data;

  private PreviewBlockData(CompoundTag data) {
    this.data = data;
  }

  public static PreviewBlockData of(CompoundTag data) {
    Objects.requireNonNull(data, "preview data");
    if (data.sizeInBytes() > 65_536) {
      throw new IllegalArgumentException("Preview block data exceeds 64 KiB");
    }
    return data.isEmpty() ? EMPTY : new PreviewBlockData(data.copy());
  }

  public CompoundTag compound() {
    return data.copy();
  }

  public boolean isEmpty() {
    return data.isEmpty();
  }

  public int sizeInBytes() {
    return isEmpty() ? 0 : data.sizeInBytes();
  }

  @Override
  public boolean equals(Object other) {
    return this == other || other instanceof PreviewBlockData value && data.equals(value.data);
  }

  @Override
  public int hashCode() {
    return data.hashCode();
  }
}
