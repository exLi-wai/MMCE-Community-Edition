package com.lw.modularmachine.client.preview;

import com.lw.modularmachine.util.minecraft.registry.RegisteredMachineItems;
import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Shared machine detail viewer for blueprints, JEI and EMI. */
public final class MachineDetailsScreen extends StructurePreviewScreen {
  private final ResourceLocation machineId;
  private ClientMachineCatalog.Machine machine;

  public MachineDetailsScreen(ClientMachineCatalog.Machine machine) {
    this(machine.id(), machine);
  }

  public MachineDetailsScreen(ResourceLocation machineId) {
    this(machineId, ClientMachineCatalog.machines().get(machineId));
  }

  private MachineDetailsScreen(ResourceLocation machineId, ClientMachineCatalog.Machine machine) {
    super(Component
        .translatable("jei.mmcece.details.title", Component.translatable(ClientMachineCatalog.translation(machineId))));
    this.machineId = Objects.requireNonNull(machineId);
    this.machine = machine;
    if (machine != null) {
      setPreview(machine.preview(), machine.color());
    }
  }

  @Override
  protected Component headerTitle() {
    return Component
        .translatable("jei.mmcece.details.title", Component.translatable(ClientMachineCatalog.translation(machineId)));
  }

  @Override
  protected ItemStack headerIcon() {
    var icon = RegisteredMachineItems.controller(machineId);

    return icon.isEmpty() ? RegisteredMachineItems.factory(machineId) : icon;
  }

  @Override
  public void tick() {
    if (minecraft.level == null) {
      onClose();
      return;
    }
    var current = ClientMachineCatalog.machines().get(machineId);
    if (current == null && (machine != null || ClientMachineCatalog.hasPublishedGeneration())) {
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
