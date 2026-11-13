package com.lw.modularmachine.machine.network.pattern;

import com.lw.modularmachine.machine.network.NetworkPortResource;
import java.util.Objects;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal exact, detached resource identity. Neither a stack count nor an AE
 * key is used as physical storage. No mutable component payload escapes the
 * key.
 */
@ApiStatus.Internal
public final class PatternResourceKey {
  private final NetworkPortResource identity;
  private final CompoundTag encoded;
  private final int hash;

  private PatternResourceKey(NetworkPortResource identity, HolderLookup.Provider registries) {
    Objects.requireNonNull(identity, "identity");
    encoded = identity.save(registries).copy();
    this.identity = NetworkPortResource.load(registries, encoded.copy());
    if (!identity.equals(this.identity)) {
      throw new IllegalArgumentException("Lossy pattern resource components");
    }
    hash = this.identity.hashCode();
  }

  public static PatternResourceKey capture(NetworkPortResource identity, HolderLookup.Provider registries) {
    return new PatternResourceKey(identity, Objects.requireNonNull(registries, "registries"));
  }

  public static PatternResourceKey load(CompoundTag tag, HolderLookup.Provider registries) {
    var identity = NetworkPortResource.load(registries, tag.copy());
    // Stored prototypes are count one; do not silently normalize damaged counts.
    if (!identity.save(registries).equals(tag)) {
      throw new IllegalArgumentException("Noncanonical pattern resource identity");
    }

    return capture(identity, registries);
  }

  public NetworkPortResource resource(HolderLookup.Provider registries) {
    return NetworkPortResource.load(registries, encoded.copy());
  }

  public CompoundTag save() {
    return encoded.copy();
  }

  @Override
  public boolean equals(Object other) {
    return this == other || other instanceof PatternResourceKey key && identity.equals(key.identity);
  }

  @Override
  public int hashCode() {
    return hash;
  }
}
