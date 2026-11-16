package com.lw.modularmachine.integration.jade;

import com.lw.modularmachine.api.controller.MachineControllerApi.StructureDiagnostic;
import com.lw.modularmachine.presentation.MachineDiagnosticText;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.ApiStatus;
import snownee.jade.api.ITooltip;

/**
 * Shared diagnostic fields and presentation; providers decide when to show
 * them.
 */
@ApiStatus.Internal
final class ControllerDiagnosticTooltip {
  private ControllerDiagnosticTooltip() {
  }

  static void capture(CompoundTag data, String prefix, StructureDiagnostic diagnostic) {
    data.putLong(prefix + "_position", diagnostic.position().asLong());
    data.putString(prefix + "_part", diagnostic.partId());
    data.putString(prefix + "_expected", diagnostic.expected());
    data.putString(prefix + "_actual", diagnostic.actual());
  }

  static void append(ITooltip tooltip, CompoundTag data, String prefix) {
    if (data.contains(prefix + "_position")) {
      tooltip.add(
          Component.translatable(
              "jade.mmcece.diagnostic.position",
              data.getString(prefix + "_part"),
              BlockPos.of(data.getLong(prefix + "_position")).toShortString()));
      tooltip.add(
          Component.translatable(
              "jade.mmcece.diagnostic.expected",
              MachineDiagnosticText.localized(data.getString(prefix + "_expected"))));
      tooltip.add(
          Component.translatable(
              "jade.mmcece.diagnostic.actual",
              MachineDiagnosticText.localized(data.getString(prefix + "_actual"))));
    }
  }
}
