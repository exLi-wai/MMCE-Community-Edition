package com.lw.modularmachine.client;

import com.lw.modularmachine.item.MachineBlueprintItem;
import com.lw.modularmachine.machine.controller.DedicatedControllerCatalog;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.network.MachinePayloads;
import java.util.Locale;
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
  static Component blueprint(AbstractContainerMenu menu) {
    if (menu == null || menu.slots.size() <= BLUEPRINT_SLOT) {
      return null;
    }
    ItemStack stack = menu.slots.get(BLUEPRINT_SLOT).getItem();
    return stack.getItem() instanceof MachineBlueprintItem item ? item.machineName() : null;
  }

  /** Found machine name, or null until the structure is confirmed. */
  static Component structure(String state, ResourceLocation machine) {
    return "CONFIRMED".equals(state) && machine != null ? machine(machine) : null;
  }

  static Component state(MachinePayloads.ControllerStatusPayload status) {
    if (!"CONFIRMED".equals(status.structureState())) {
      return "PENDING".equals(status.structureState()) ? tr(STATE + "pending") : tr(STATE + "missing_structure");
    }
    return switch (status.recipeStatus()) {
      case "IDLE" -> status.recipeId().isEmpty() ? tr(STATE + "no_recipe") : tr(STATE + "idle");
      case "ADVANCED", "WAITING_TICK" -> tr(STATE + "crafting");
      case "WAITING_ENERGY" -> tr(STATE + "missing_energy");
      case "WAITING_FLUID" -> tr(STATE + "missing_fluid");
      case "MISSING_ITEM_INPUT" -> tr(STATE + "missing_item_input");
      case "MISSING_ITEM_OUTPUT" -> tr(STATE + "missing_item_output");
      case "MISSING_ENERGY_INPUT" -> tr(STATE + "missing_energy_input");
      case "MISSING_FLUID_INPUT" -> tr(STATE + "missing_fluid_input");
      case "MISSING_FLUID_OUTPUT" -> tr(STATE + "missing_fluid_output");
      case "OUTPUT_PLANNING" -> tr(STATE + "output_planning");
      case "OUTPUT_BLOCKED" -> tr(STATE + "output_blocked");
      case "FINISHED" -> tr(STATE + "finished");
      case "PAUSED" -> tr(STATE + "paused");
      case "BINDING_CHANGED" -> tr(STATE + "binding_changed");
      case "STALE" -> tr(STATE + "stale");
      case "ERROR" -> tr(STATE + "error");
      case "WAITING_UPGRADE_COST" -> tr(STATE + "waiting_upgrade_cost");
      case "UPGRADE_DATA_PENDING" -> tr(STATE + "upgrade_data_pending");
      case "UPGRADE_CONFLICT" -> tr(STATE + "upgrade_conflict");
      case "UPGRADE_ERROR" -> tr(STATE + "upgrade_error");
      default -> tr(STATE + status.recipeStatus().toLowerCase(Locale.ROOT));
    };
  }

  static Component factoryState(MachinePayloads.FactoryStatusPayload status) {
    if (!"CONFIRMED".equals(status.structureState())) {
      return "PENDING".equals(status.structureState()) ? tr(STATE + "pending") : tr(STATE + "missing_structure");
    }
    if (!status.persistentStateValid()) {
      return tr(STATE + "opaque");
    }
    if (status.threads().stream().anyMatch(thread -> "RUNNING".equals(thread.state()))) {
      return tr(STATE + "crafting");
    }
    for (var thread : status.threads()) {
      if (!"IDLE".equals(thread.state()) && !"NONE".equals(thread.reason())) {
        return tr("menu.mmcece.factory.reason." + thread.reason().toLowerCase(Locale.ROOT));
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
        String.format(Locale.ROOT, "%.2f", averageSearchMicros / 1000.0));
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

  static int percent(int progress, int duration) {
    if (duration <= 0) {
      return 0;
    }
    return (int) Math.max(0L, Math.min(100L, (long) progress * 100L / duration));
  }

  static Component none() {
    return tr("menu.mmcece.controller.none");
  }

  private static Component machine(ResourceLocation id) {
    return Component.translatableWithFallback(DedicatedControllerCatalog.translation(id), id.getPath());
  }

  private static Component tr(String key) {
    return Component.translatable(key);
  }
}
