package com.lw.modularmachine.machine.network;

import com.lw.modularmachine.api.component.ResourceType;
import com.lw.modularmachine.machine.network.codec.NetworkResourceCodecs;
import java.util.Objects;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
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
public interface NetworkPortResource {
  StreamCodec<RegistryFriendlyByteBuf, NetworkPortResource> STREAM_CODEC = StreamCodec
      .of(NetworkResourceCodecs::write, NetworkResourceCodecs::read);

  ResourceType kind();

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
    public ResourceType kind() {
      return ResourceType.ITEM;
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
    public ResourceType kind() {
      return ResourceType.FLUID;
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
    public ResourceType kind() {
      return ResourceType.CHEMICAL;
    }
  }

  default CompoundTag save(HolderLookup.Provider registries) {
    return NetworkResourceCodecs.save(registries, this);
  }

  static NetworkPortResource load(HolderLookup.Provider registries, CompoundTag tag) {
    return NetworkResourceCodecs.load(registries, tag);
  }
}
