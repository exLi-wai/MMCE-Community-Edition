package com.lw.modularmachine.presentation;

import java.util.Locale;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class ControllerStatusFormat {
  private ControllerStatusFormat() {
  }

  public static String progressPercent(int progressTicks, int durationTicks) {
    if (durationTicks <= 0) {
      return "0%";
    }
    long percent = 100L * progressTicks / durationTicks;
    return Math.clamp(percent, 0, 100) + "%";
  }

  public static String searchMilliseconds(int averageSearchMicros) {
    return String.format(Locale.ROOT, "%.3f", averageSearchMicros / 1000.0);
  }

  public static MutableComponent menuRecipeStatus(String status, boolean hasRecipe) {
    String label = switch (status) {
      case "IDLE" -> hasRecipe ? "idle" : "no_recipe";
      case "ADVANCED", "WAITING_TICK" -> "crafting";
      case "WAITING_ENERGY" -> "missing_energy";
      case "WAITING_FLUID" -> "missing_fluid";
      default -> status.toLowerCase(Locale.ROOT);
    };
    return Component.translatable("menu.mmcece.controller.state." + label);
  }

  public static Optional<Component> jadeRecipeStatus(String status, boolean hasRun) {
    if (hasRun && !showsResourceWait(status)) {
      return Optional.empty();
    }
    return Optional.of(Component.translatable("jade.mmcece.run_status." + status.toLowerCase(Locale.ROOT)));
  }

  private static boolean showsResourceWait(String status) {
    return switch (status) {
      case "WAITING_RESOURCE", "MISSING_RESOURCE_INPUT", "WAITING_ENERGY", "WAITING_FLUID", "WAITING_CHEMICAL",
          "OUTPUT_BLOCKED", "OUTPUT_PLANNING", "MISSING_ITEM_INPUT", "MISSING_ITEM_OUTPUT", "MISSING_ENERGY_INPUT",
          "MISSING_ENERGY_OUTPUT", "MISSING_FLUID_INPUT", "MISSING_FLUID_OUTPUT", "MISSING_CHEMICAL_INPUT",
          "MISSING_CHEMICAL_OUTPUT" ->
        true;
      default -> false;
    };
  }

  public static MutableComponent factoryThreadState(String state) {
    return Component.translatable("menu.mmcece.factory.thread_state." + state.toLowerCase(Locale.ROOT));
  }

  public static MutableComponent factoryThreadReason(String reason) {
    return Component.translatable("menu.mmcece.factory.reason." + reason.toLowerCase(Locale.ROOT));
  }

  public static MutableComponent structureFailure(String failure) {
    return Component.translatable("jade.mmcece.failure." + failure.toLowerCase(Locale.ROOT));
  }
}
