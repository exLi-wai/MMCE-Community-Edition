package com.lw.modularmachine.machine.network;

import com.lw.modularmachine.machine.PortKind;
import java.util.Objects;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal quantity-free resource identity shared by buffers and network
 * adapters.
 */
@ApiStatus.Internal
public sealed interface NetworkPortResource {
  StreamCodec<RegistryFriendlyByteBuf, NetworkPortResource> STREAM_CODEC = StreamCodec
      .of(NetworkPortResource::write, NetworkPortResource::read);

  PortKind.Resource kind();

  record Item(ItemStack stack) implements NetworkPortResource {
    public Item {
      if (Objects.requireNonNull(stack, "stack").isEmpty()) {
        throw new IllegalArgumentException("Empty network item identity");
      }
      stack = stack.copyWithCount(1);
    }

    @Override
    public ItemStack stack() {
      return stack.copy();
    }

    @Override
    public PortKind.Resource kind() {
      return PortKind.Resource.ITEM;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Item value && ItemStack.isSameItemSameComponents(stack, value.stack);
    }

    @Override
    public int hashCode() {
      return 31 * System.identityHashCode(stack.getItem()) + stack.getComponents().hashCode();
    }
  }

  record Fluid(FluidStack stack) implements NetworkPortResource {
    public Fluid {
      if (Objects.requireNonNull(stack, "stack").isEmpty()) {
        throw new IllegalArgumentException("Empty network fluid identity");
      }
      stack = stack.copyWithAmount(1);
    }

    @Override
    public FluidStack stack() {
      return stack.copy();
    }

    @Override
    public PortKind.Resource kind() {
      return PortKind.Resource.FLUID;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Fluid value && FluidStack.isSameFluidSameComponents(stack, value.stack);
    }

    @Override
    public int hashCode() {
      return 31 * System.identityHashCode(stack.getFluid()) + stack.getComponents().hashCode();
    }
  }

  record Chemical(ResourceLocation id) implements NetworkPortResource {
    public Chemical {
      Objects.requireNonNull(id, "id");
    }

    @Override
    public PortKind.Resource kind() {
      return PortKind.Resource.CHEMICAL;
    }
  }

  default CompoundTag save(HolderLookup.Provider registries) {
    var result = new CompoundTag();
    result.putString("kind", kind().name());
    var ops = registries.createSerializationContext(NbtOps.INSTANCE);
    switch (this) {
      case Item item -> result.put("value", ItemStack.CODEC.encodeStart(ops, item.stack()).getOrThrow());
      case Fluid fluid -> result.put("value", FluidStack.CODEC.encodeStart(ops, fluid.stack()).getOrThrow());
      case Chemical chemical -> result.putString("value", chemical.id().toString());
    }

    return result;
  }

  static NetworkPortResource load(HolderLookup.Provider registries, CompoundTag tag) {
    if (!tag.contains("kind", Tag.TAG_STRING) || !tag.contains("value")) {
      throw new IllegalArgumentException("Missing network resource identity");
    }
    var ops = registries.createSerializationContext(NbtOps.INSTANCE);

    return switch (PortKind.Resource.valueOf(tag.getString("kind"))) {
      case ITEM -> new Item(ItemStack.CODEC.parse(ops, tag.get("value")).getOrThrow());
      case FLUID -> new Fluid(FluidStack.CODEC.parse(ops, tag.get("value")).getOrThrow());
      case CHEMICAL -> new Chemical(ResourceLocation.parse(tag.getString("value")));
      case ENERGY -> throw new IllegalArgumentException("Unsupported network resource identity");
    };
  }

  private static void write(RegistryFriendlyByteBuf buffer, NetworkPortResource value) {
    buffer.writeEnum(value.kind());
    switch (value) {
      case Item item -> ItemStack.STREAM_CODEC.encode(buffer, item.stack());
      case Fluid fluid -> FluidStack.STREAM_CODEC.encode(buffer, fluid.stack());
      case Chemical chemical -> buffer.writeResourceLocation(chemical.id());
    }
  }

  private static NetworkPortResource read(RegistryFriendlyByteBuf buffer) {
    return switch (buffer.readEnum(PortKind.Resource.class)) {
      case ITEM -> new Item(ItemStack.STREAM_CODEC.decode(buffer));
      case FLUID -> new Fluid(FluidStack.STREAM_CODEC.decode(buffer));
      case CHEMICAL -> new Chemical(buffer.readResourceLocation());
      case ENERGY -> throw new IllegalArgumentException("Unsupported network resource identity");
    };
  }
}
