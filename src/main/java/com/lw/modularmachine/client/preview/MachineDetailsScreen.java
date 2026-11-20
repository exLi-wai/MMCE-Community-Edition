package com.lw.modularmachine.client.preview;

import com.lw.modularmachine.util.minecraft.registry.RegisteredMachineItems;
import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.Items;

/** Shared machine detail viewer for blueprints, JEI and EMI. */
public final class MachineDetailsScreen extends StructurePreviewScreen {
  private final ResourceLocation machineId;
  private ClientMachineCatalog.Machine machine;
  private int modifierIndex;

  public MachineDetailsScreen(ClientMachineCatalog.Machine machine) {
    this(machine.id(), machine);
  }

  public MachineDetailsScreen(ResourceLocation machineId) {
    this(machineId, ClientMachineCatalog.machines().get(machineId));
  }

  private MachineDetailsScreen(ResourceLocation machineId, ClientMachineCatalog.Machine machine) {
    super(detailsTitle(machineId));
    this.machineId = Objects.requireNonNull(machineId);
    this.machine = machine;
    if (machine != null) {
      setPreview(machine.preview(), machine.color());
    }
  }

  private static Component detailsTitle(ResourceLocation machineId) {
    return Component
        .translatable("jei.mmcece.details.title", Component.translatable(ClientMachineCatalog.translation(machineId)));
  }

  @Override
  protected Component headerTitle() {
    var name = detailsTitle(machineId);
    return machine == null || machine.prefix().isEmpty() ? name : Component.translatable(machine.prefix()).append(" ")
        .append(name);
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

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    super.render(graphics, mouseX, mouseY, partialTick);
    if (machine == null || machine.modifiers().isEmpty()) {
      return;
    }
    var panel = StructurePreviewPanel.forScreen(width, height);
    modifierIndex = Math.floorMod(modifierIndex, machine.modifiers().size());
    var modifier = machine.modifiers().get(modifierIndex);
    var stack = modifier.display().stack();
    if (stack.isEmpty()) {
      stack = new ItemStack(Items.BOOK);
    }
    int x = panel.left() + panel.width() - 34;
    int y = panel.top() + 12;
    graphics.renderItem(stack, x, y);
    if (modifierContains(mouseX, mouseY)) {
      var text = new ArrayList<Component>();
      text.add(Component.translatable(modifier.display().name().isEmpty() ? modifier.id() : modifier.display().name()));
      if (!modifier.display().description().isEmpty()) {
        text.add(Component.translatable(modifier.display().description()));
      }
      text.addAll(Screen.getTooltipFromItem(minecraft, stack));
      text.add(Component.literal((modifierIndex + 1) + " / " + machine.modifiers().size()));
      text.add(Component.translatable("jei.mmcece.modifier_cycle"));
      graphics.renderTooltip(font, text, Optional.empty(), mouseX, mouseY);
    }
  }

  private boolean modifierContains(double mouseX, double mouseY) {
    var panel = StructurePreviewPanel.forScreen(width, height);
    int x = panel.left() + panel.width() - 34;
    int y = panel.top() + 12;
    return mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18;
  }

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    if (machine != null
        && !machine.modifiers().isEmpty()
        && modifierContains(mouseX, mouseY)
        && (button == 0 || button == 1)) {
      modifierIndex = Math.floorMod(modifierIndex + (button == 0 ? 1 : -1), machine.modifiers().size());
      return true;
    }
    return super.mouseClicked(mouseX, mouseY, button);
  }
}
