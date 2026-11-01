package com.lw.modularmachine.machine.blueprint;

import com.lw.modularmachine.item.MachineBlueprintItem;
import com.lw.modularmachine.item.PortableBlueprintItem;
import net.minecraft.world.item.ItemStack;

/**
 * Single read-only view over both blueprint families: registered blueprints
 * carry machine identity in the item, the portable item carries it in its
 * component.
 */
public final class BlueprintBindings {
  private BlueprintBindings() {
  }

  public static MachineBlueprint.Binding binding(ItemStack stack) {
    var registered = MachineBlueprintItem.binding(stack);
    if (registered != null) {
      return registered;
    }
    var machine = PortableBlueprintItem.machine(stack);
    if (machine == null) {
      return null;
    }
    return new MachineBlueprint.Binding(machine, PortableBlueprintItem.pose(stack));
  }
}
