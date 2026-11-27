package com.lw.modularmachine.api.recipe;

import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * The optional item or fluid prototype shared by ingredient choices and
 * scheduled IO.
 */
public final class RecipeStackPrototype {
  static final MapCodec<DynamicOps<Tag>> COMPONENT_OPS = ExtraCodecs.retrieveContext(
      ops -> DataResult.success(
          ops instanceof RegistryOps<?> registries ? registries.withParent(NbtOps.INSTANCE) : NbtOps.INSTANCE));
  private static final RecipeStackPrototype EMPTY = new RecipeStackPrototype(null, null);
  private final ItemAmount.Prototype item;
  private final FluidRequirement fluid;

  private RecipeStackPrototype(ItemAmount.Prototype item, FluidRequirement fluid) {
    this.item = item;
    this.fluid = fluid;
  }

  static RecipeStackPrototype of(Optional<ItemStack> item, Optional<FluidStack> fluid) {
    if (item.isPresent()) {
      var stack = item.orElseThrow();
      if (stack.isEmpty() || stack.getCount() != 1 || fluid.isPresent()) {
        throw new IllegalArgumentException("A recipe prototype requires exactly one item or fluid");
      }
      return new RecipeStackPrototype(ItemAmount.Prototype.of(stack), null);
    }
    return fluid.map(stack -> new RecipeStackPrototype(null, new FluidRequirement(stack, 1))).orElse(EMPTY);
  }

  RecipeStackPrototype capture(HolderLookup.Provider registries) {
    var capturedItem = item != null && item.needsCapture() ? item
        .capture(registries.createSerializationContext(NbtOps.INSTANCE)) : item;
    var capturedFluid = fluid == null ? null : fluid.capture(registries);
    return capturedItem == item
        && capturedFluid == fluid ? this : new RecipeStackPrototype(capturedItem, capturedFluid);
  }

  RecipeStackPrototype capture(DynamicOps<Tag> ops) {
    var capturedItem = item == null ? null : item.capture(ops);
    var capturedFluid = fluid == null ? null : fluid.capture(ops);
    return capturedItem == item
        && capturedFluid == fluid ? this : new RecipeStackPrototype(capturedItem, capturedFluid);
  }

  public Optional<ItemStack> item() {
    return item == null ? Optional.empty() : Optional.of(item.copy());
  }

  boolean hasItem() {
    return item != null;
  }

  boolean hasFluid() {
    return fluid != null;
  }

  boolean unitFluid() {
    return fluid != null && fluid.amount() == 1;
  }

  public Optional<FluidStack> fluid() {
    return fluid == null ? Optional.empty() : Optional.of(fluid.stack());
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof RecipeStackPrototype value
            && (item == value.item
                || item != null && value.item != null && ItemStack.matches(item.stack(), value.item.stack()))
            && Objects.equals(fluid, value.fluid);
  }

  @Override
  public int hashCode() {
    return item == null ? Objects.hashCode(fluid) : ItemStack.hashItemAndComponents(item.stack());
  }
}
