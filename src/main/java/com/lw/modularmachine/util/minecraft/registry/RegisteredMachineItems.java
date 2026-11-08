package com.lw.modularmachine.util.minecraft.registry;

import com.lw.modularmachine.block.FactoryControllerBlock;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.registry.MachineRegistries;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal registered-machine identity queries, available after registration.
 * Missing identities stay empty; callers own any generic-controller fallback.
 */
@ApiStatus.Internal
public final class RegisteredMachineItems {
  private RegisteredMachineItems() {
  }

  public static Optional<MachineControllerBlock> controllerBlock(ResourceLocation machine) {
    return MachineRegistries.DEDICATED_CONTROLLERS.values().stream().map(holder -> holder.get())
        .filter(block -> block.dedicated() != null && block.dedicated().machine().equals(machine)).findFirst();
  }

  public static Optional<FactoryControllerBlock> factoryBlock(ResourceLocation machine) {
    return MachineRegistries.DEDICATED_FACTORIES.values().stream().map(holder -> holder.get())
        .filter(block -> block.dedicated() != null && block.dedicated().machine().equals(machine)).findFirst();
  }

  /**
   * Returns a fresh stack of the independently registered ordinary controller.
   */
  public static ItemStack controller(ResourceLocation machine) {
    return controllerBlock(machine).map(ItemStack::new).orElse(ItemStack.EMPTY);
  }

  /** Returns a fresh stack of the independently registered factory controller. */
  public static ItemStack factory(ResourceLocation machine) {
    return factoryBlock(machine).map(ItemStack::new).orElse(ItemStack.EMPTY);
  }

  /** The blueprint identity comes from registration, never from item data. */
  public static ItemStack blueprint(ResourceLocation machine) {
    var registered = MachineRegistries.BLUEPRINTS.get(machine);
    return registered == null ? ItemStack.EMPTY : registered.get().getDefaultInstance();
  }
}
