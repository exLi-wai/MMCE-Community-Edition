package com.lw.modularmachine.integration.jei;

import com.lw.modularmachine.client.preview.ClientMachineCatalog;
import com.lw.modularmachine.client.preview.StructurePreviewScreen;
import com.lw.modularmachine.util.minecraft.registry.RegisteredMachineItems;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** JEI catalog adapter for the shared full-screen structure viewer. */
public final class MachineDetailsScreen extends StructurePreviewScreen {
  private ClientMachineCatalog.Machine machine;

  public MachineDetailsScreen(ClientMachineCatalog.Machine machine) {
    super(Component.translatable("jei.mmcece.details.title", Component.translatable(machine.translation())));
    this.machine = machine;
    setPreview(machine.preview(), machine.color());
  }

  @Override
  protected Component headerTitle() {
    return Component.translatable("jei.mmcece.details.title", Component.translatable(machine.translation()));
  }

  @Override
  protected ItemStack headerIcon() {
    var icon = RegisteredMachineItems.controller(machine.id());

    return icon.isEmpty() ? RegisteredMachineItems.factory(machine.id()) : icon;
  }

  @Override
  public void tick() {
    if (minecraft.level == null) {
      onClose();
      return;
    }
    var current = ClientMachineCatalog.machines().get(machine.id());
    if (current == null) {
      onClose();
      return;
    }
    if (current != machine) {
      machine = current;
      setPreview(machine.preview(), machine.color());
    }
    super.tick();
  }
}
