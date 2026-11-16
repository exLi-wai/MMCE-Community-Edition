package com.lw.modularmachine.machine.network.codec;

import com.lw.modularmachine.api.component.ResourceType;
import com.lw.modularmachine.machine.network.NetworkPortResource;
import com.mojang.serialization.Codec;
import java.util.Objects;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal typed codecs for the quantity-free value of one network resource.
 */
@ApiStatus.Internal
public record NetworkResourceCodec<T extends NetworkPortResource>(
    ResourceType kind,
    Class<T> valueClass,
    Codec<T> valueCodec,
    StreamCodec<RegistryFriendlyByteBuf, T> valueStreamCodec) {
  public NetworkResourceCodec {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(valueClass, "valueClass");
    Objects.requireNonNull(valueCodec, "valueCodec");
    Objects.requireNonNull(valueStreamCodec, "valueStreamCodec");
  }

  Tag save(HolderLookup.Provider registries, NetworkPortResource value) {
    var ops = registries.createSerializationContext(NbtOps.INSTANCE);

    return valueCodec.encodeStart(ops, requireValue(value)).getOrThrow();
  }

  T load(HolderLookup.Provider registries, Tag encoded) {
    var ops = registries.createSerializationContext(NbtOps.INSTANCE);

    return requireValue(valueCodec.parse(ops, encoded).getOrThrow());
  }

  void write(RegistryFriendlyByteBuf buffer, NetworkPortResource value) {
    valueStreamCodec.encode(buffer, requireValue(value));
  }

  T read(RegistryFriendlyByteBuf buffer) {
    return requireValue(valueStreamCodec.decode(buffer));
  }

  private T requireValue(NetworkPortResource value) {
    if (Objects.requireNonNull(value, "value").kind() != kind || !valueClass.isInstance(value)) {
      throw new IllegalArgumentException("Network resource value does not match codec: " + kind);
    }

    return valueClass.cast(value);
  }
}
