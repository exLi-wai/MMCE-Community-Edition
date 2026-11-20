package com.lw.modularmachine.client;

import com.lw.modularmachine.item.MachineBlueprintItem;
import com.lw.modularmachine.machine.controller.DedicatedControllerCatalog;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.network.MachinePayloads;
import com.lw.modularmachine.presentation.ControllerStatusFormat;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** Shared original-style labels for the controller status panels. */
final class ControllerStatusLabels {
  private static final int BLUEPRINT_SLOT = 36;
  private static final String STATE = "menu.mmcece.controller.state.";

  private ControllerStatusLabels() {
  }

  /** Blueprint machine name, or null when the blueprint slot is empty. */
  static Component blueprintName(AbstractContainerMenu menu) {
    if (menu == null || menu.slots.size() <= BLUEPRINT_SLOT) {
      return null;
    }
    ItemStack stack = menu.slots.get(BLUEPRINT_SLOT).getItem();
    return stack.getItem() instanceof MachineBlueprintItem item ? item.machineName() : null;
  }

  static Component blueprintLine(Component blueprintName) {
    return Component.translatable("menu.mmcece.controller.blueprint", blueprintName == null ? none() : blueprintName);
  }

  static Component structureLine(String state, ResourceLocation machineId) {
    Component name = "CONFIRMED".equals(state) && machineId != null ? machine(machineId) : none();
    return Component.translatable("menu.mmcece.controller.structure", name);
  }

  static Component progressLine(int progressTicks, int durationTicks) {
    return Component.translatable(
        "menu.mmcece.controller.progress",
        ControllerStatusFormat.progressPercent(progressTicks, durationTicks));
  }

  static Component state(MachinePayloads.ControllerStatusPayload status) {
    if (!"CONFIRMED".equals(status.structureState())) {
      return unconfirmedStructure(status.structureState());
    }
    if (!status.recipeFailureMessage().isEmpty()) {
      return Component.translatable(status.recipeFailureMessage());
    }
    return ControllerStatusFormat.menuRecipeStatus(status.recipeStatus(), !status.recipeId().isEmpty());
  }

  static Component factoryState(MachinePayloads.FactoryStatusPayload status) {
    if (!"CONFIRMED".equals(status.structureState())) {
      return unconfirmedStructure(status.structureState());
    }
    if (!status.persistentStateValid()) {
      return tr(STATE + "opaque");
    }
    if (status.threads().stream().anyMatch(thread -> "RUNNING".equals(thread.state()))) {
      return tr(STATE + "crafting");
    }
    for (var thread : status.threads()) {
      if (!thread.recipeFailureMessage().isEmpty()) {
        return Component.translatable(thread.recipeFailureMessage());
      }
      if (!"IDLE".equals(thread.state()) && !"NONE".equals(thread.reason())) {
        return ControllerStatusFormat.factoryThreadReason(thread.reason());
      }
    }
    if (status.threads().stream().anyMatch(thread -> !"IDLE".equals(thread.state()))) {
      return tr(STATE + "waiting");
    }
    return tr(STATE + "idle");
  }

  /** Original-style "Avg: xµs/t (Search: yms)" line. */
  static Component performance(int averageTickMicros, int averageSearchMicros) {
    return Component.translatable(
        "menu.mmcece.controller.performance",
        averageTickMicros,
        ControllerStatusFormat.searchMilliseconds(averageSearchMicros));
  }

  /** Draws the average line and a second green WorkMode line. */
  static void drawPerformance(GuiGraphics graphics, Font font, int x, int y, int averageTickMicros, int averageSearchMicros) {
    Component performance = performance(averageTickMicros, averageSearchMicros);
    Component prefix = Component.literal("WorkMode: ");
    graphics.drawString(font, performance, x, y, 0xFFFFFF, true);
    graphics.drawString(font, prefix, x, y + 10, 0xFFFFFF, true);
    graphics.drawString(
        font,
        Component.literal(StructureService.workMode()),
        x + font.width(prefix.getString()),
        y + 10,
        0x55FF55,
        true);
  }

  private static Component none() {
    return tr("menu.mmcece.controller.none");
  }

  private static Component unconfirmedStructure(String state) {
    return tr(STATE + ("PENDING".equals(state) ? "pending" : "missing_structure"));
  }

  private static Component machine(ResourceLocation id) {
    return Component.translatableWithFallback(DedicatedControllerCatalog.translation(id), id.getPath());
  }

  private static Component tr(String key) {
    return Component.translatable(key);
  }
}
