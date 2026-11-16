package com.lw.modularmachine.machine.network.codec;

import com.lw.modularmachine.api.component.ResourceType;
import com.lw.modularmachine.machine.component.ResourceTypeRegistry;
import com.lw.modularmachine.machine.network.NetworkPortResource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal codec directory frozen by its first use. The envelope keeps native
 * kind names and wire identities; descriptors encode only the resource value.
 */
@ApiStatus.Internal
public final class NetworkResourceCodecs {
  private static final Map<ResourceType, NetworkResourceCodec<?>> REGISTERED = new LinkedHashMap<>();
  private static volatile Map<ResourceType, NetworkResourceCodec<?>> snapshot;

  static {
    register(
        new NetworkResourceCodec<>(
            ResourceType.ITEM,
            NetworkPortResource.Item.class,
            ItemStack.CODEC.xmap(NetworkPortResource.Item::new, NetworkPortResource.Item::stack),
            ItemStack.STREAM_CODEC.map(NetworkPortResource.Item::new, NetworkPortResource.Item::stack)));
    register(
        new NetworkResourceCodec<>(
            ResourceType.FLUID,
            NetworkPortResource.Fluid.class,
            FluidStack.CODEC.xmap(NetworkPortResource.Fluid::new, NetworkPortResource.Fluid::stack),
            FluidStack.STREAM_CODEC.map(NetworkPortResource.Fluid::new, NetworkPortResource.Fluid::stack)));
    register(
        new NetworkResourceCodec<>(
            ResourceType.CHEMICAL,
            NetworkPortResource.Chemical.class,
            ResourceLocation.CODEC.xmap(NetworkPortResource.Chemical::new, NetworkPortResource.Chemical::id),
            StreamCodec.of(
                (buffer, value) -> buffer.writeResourceLocation(value.id()),
                buffer -> new NetworkPortResource.Chemical(buffer.readResourceLocation()))));
  }

  public static final StreamCodec<RegistryFriendlyByteBuf, NetworkPortResource> STREAM_CODEC = StreamCodec
      .of(NetworkResourceCodecs::write, NetworkResourceCodecs::read);

  private NetworkResourceCodecs() {
  }

  /**
   * Install pure value codecs during startup, before any persisted or wire value
   * is used.
   */
  public static synchronized <T extends NetworkPortResource> void register(NetworkResourceCodec<T> codec) {
    Objects.requireNonNull(codec, "codec");
    if (snapshot != null) {
      throw new IllegalStateException("Network resource codecs are frozen");
    }
    if (REGISTERED.putIfAbsent(codec.kind(), codec) != null) {
      throw new IllegalArgumentException("Duplicate network resource codec: " + codec.kind());
    }
  }

  private static synchronized Map<ResourceType, NetworkResourceCodec<?>> freeze() {
    if (snapshot == null) {
      snapshot = Map.copyOf(REGISTERED);
    }

    return snapshot;
  }

  public static CompoundTag save(HolderLookup.Provider registries, NetworkPortResource value) {
    Objects.requireNonNull(value, "value");
    var codec = requireCodec(value.kind());
    var result = new CompoundTag();
    result.putString("kind", codec.kind().name());
    result.put("value", codec.save(registries, value));

    return result;
  }

  public static NetworkPortResource load(HolderLookup.Provider registries, CompoundTag tag) {
    if (!tag.contains("kind", Tag.TAG_STRING) || !tag.contains("value")) {
      throw new IllegalArgumentException("Missing network resource identity");
    }

    return requireCodec(ResourceType.valueOf(tag.getString("kind"))).load(registries, tag.get("value"));
  }

  /** Stored pattern prototypes must round-trip exactly, including count one. */
  public static NetworkPortResource loadCanonical(HolderLookup.Provider registries, CompoundTag tag) {
    var value = load(registries, tag);
    if (!save(registries, value).equals(tag)) {
      throw new IllegalArgumentException("Noncanonical pattern resource identity");
    }

    return value;
  }

  public static void write(RegistryFriendlyByteBuf buffer, NetworkPortResource value) {
    var codec = requireCodec(value.kind());
    codec.kind().write(buffer);
    codec.write(buffer, value);
  }

  public static NetworkPortResource read(RegistryFriendlyByteBuf buffer) {
    return requireCodec(ResourceType.read(buffer)).read(buffer);
  }

  private static NetworkResourceCodec<?> requireCodec(ResourceType kind) {
    ResourceTypeRegistry.requireRegistered(kind);
    var codecs = snapshot;
    if (codecs == null) {
      codecs = freeze();
    }
    var codec = codecs.get(kind);
    if (codec == null) {
      throw new IllegalArgumentException("Unsupported network resource identity");
    }

    return codec;
  }
}
