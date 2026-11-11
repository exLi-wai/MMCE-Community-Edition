package com.lw.modularmachine.integration.jade;

import com.lw.modularmachine.api.controller.MachineControllerApi;
import com.lw.modularmachine.machine.recipe.ControllerMetrics;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.machine.recipe.FrozenItemRun;
import com.lw.modularmachine.machine.structure.VerificationState;
import com.lw.modularmachine.presentation.MachineDiagnosticText;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

final class MachineControllerTooltipProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
  static final MachineControllerTooltipProvider INSTANCE = new MachineControllerTooltipProvider();
  private static final String PREFIX = "mmcece:";
  private static final String STRUCTURE = PREFIX + "structure";
  private static final String FAILURE = PREFIX + "failure";
  private static final String PRODUCTION = PREFIX + "production";
  private static final String RECIPE = PREFIX + "recipe";
  private static final String RUN_STATE = PREFIX + "run_state";
  private static final String PROGRESS = PREFIX + "progress";
  private static final String RUN_STATUS = PREFIX + "run_status";
  private static final String PERFORMANCE = PREFIX + "performance";
  private static final String DIAGNOSTIC = PREFIX + "diagnostic";
  private static final String INPUT_DIAGNOSTIC = PREFIX + "input_diagnostic";

  private MachineControllerTooltipProvider() {
  }

  @Override
  public ResourceLocation getUid() {
    return ResourceLocation.fromNamespaceAndPath("mmcece", "controller_status");
  }

  @Override
  public boolean shouldRequestData(BlockAccessor accessor) {
    return accessor.getBlockEntity() instanceof MachineControllerBlockEntity;
  }

  @Override
  public void appendServerData(CompoundTag data, BlockAccessor accessor) {
    if (!(accessor.getBlockEntity() instanceof MachineControllerBlockEntity controller)) {
      return;
    }
    if (controller.getLevel() instanceof ServerLevel level) {
      ControllerOwnershipTooltip.appendServerData(data, level, controller.getBlockPos());
    }
    MachineControllerApi.inspect(controller).ifPresent(snapshot -> {
      data.putString(STRUCTURE, snapshot.structureState().name());
      data.putString(FAILURE, snapshot.structureFailure().name());
      data.putBoolean(PRODUCTION, snapshot.productionAllowed());
      data.putString(RUN_STATUS, snapshot.recipeRunStatus().name());
      data.putInt(PERFORMANCE + "_tick", ControllerMetrics.averageTickMicros());
      data.putInt(PERFORMANCE + "_search", ControllerMetrics.averageSearchMicros());
      snapshot.structureDiagnostic().ifPresent(diagnostic -> {
        data.putLong(DIAGNOSTIC + "_position", diagnostic.position().asLong());
        data.putString(DIAGNOSTIC + "_part", diagnostic.partId());
        data.putString(DIAGNOSTIC + "_expected", diagnostic.expected());
        data.putString(DIAGNOSTIC + "_actual", diagnostic.actual());
      });
      snapshot.recipeInputDiagnostic().ifPresent(diagnostic -> {
        data.putString(INPUT_DIAGNOSTIC + "_recipe", diagnostic.recipeId().toString());
        var shortages = diagnostic.shortages();
        data.putInt(INPUT_DIAGNOSTIC + "_count", shortages.size());
        for (int i = 0; i < shortages.size(); i++) {
          var shortage = shortages.get(i);
          data.putString(INPUT_DIAGNOSTIC + "_key_" + i, shortage.key());
          data.putString(INPUT_DIAGNOSTIC + "_description_" + i, shortage.descriptionId());
          data.putLong(INPUT_DIAGNOSTIC + "_missing_" + i, shortage.missing());
        }
      });
      snapshot.runningRun().ifPresentOrElse(run -> {
        data.putString(RECIPE, run.recipeId().toString());
        data.putString(RUN_STATE, run.state().name());
        data.putInt("mmcece:progress_value", run.progress());
        data.putInt("mmcece:duration_value", run.duration());
      }, () -> data.putBoolean("mmcece:has_run", false));
      if (snapshot.runningRun().isPresent()) {
        data.putBoolean("mmcece:has_run", true);
      }
    });
  }

  @Override
  public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
    var data = accessor.getServerData();
    ControllerOwnershipTooltip.appendTooltip(tooltip, accessor);
    if (!data.contains(STRUCTURE)) {
      return;
    }
    var structure = enumValue(VerificationState.class, data.getString(STRUCTURE));
    if (structure == null) {
      return;
    }
    tooltip.add(Component.translatable("jade.mmcece.structure." + structure.name().toLowerCase(Locale.ROOT)));
    tooltip.add(
        Component.translatable(
            data.getBoolean(PRODUCTION) ? "jade.mmcece.production.ready" : "jade.mmcece.production.paused"));
    if (structure == VerificationState.INVALID) {
      tooltip.add(Component.translatable("jade.mmcece.failure." + data.getString(FAILURE).toLowerCase(Locale.ROOT)));
      if (data.contains(DIAGNOSTIC + "_position")) {
        tooltip.add(
            Component.translatable(
                "jade.mmcece.diagnostic.position",
                data.getString(DIAGNOSTIC + "_part"),
                BlockPos.of(data.getLong(DIAGNOSTIC + "_position")).toShortString()));
        tooltip.add(
            Component.translatable(
                "jade.mmcece.diagnostic.expected",
                MachineDiagnosticText.localized(data.getString(DIAGNOSTIC + "_expected"))));
        tooltip.add(
            Component.translatable(
                "jade.mmcece.diagnostic.actual",
                MachineDiagnosticText.localized(data.getString(DIAGNOSTIC + "_actual"))));
      }
    }
    if (data.contains(INPUT_DIAGNOSTIC + "_count")) {
      tooltip.add(Component.translatable("jade.mmcece.input.recipe", data.getString(INPUT_DIAGNOSTIC + "_recipe")));
      int count = Math.min(data.getInt(INPUT_DIAGNOSTIC + "_count"), 8);
      for (int i = 0; i < count; i++) {
        String description = data.getString(INPUT_DIAGNOSTIC + "_description_" + i);
        Component item;
        if (description.isEmpty()) {
          item = Component.literal(data.getString(INPUT_DIAGNOSTIC + "_key_" + i));
        } else {
          item = Component.translatable(description);
        }
        tooltip.add(
            Component
                .translatable("jade.mmcece.input.shortage", data.getLong(INPUT_DIAGNOSTIC + "_missing_" + i), item));
      }
    }
    if (data.getBoolean("mmcece:has_run")) {
      var runState = enumValue(FrozenItemRun.State.class, data.getString(RUN_STATE));
      if (runState != null) {
        tooltip.add(Component.translatable("jade.mmcece.recipe", data.getString(RECIPE)));
        tooltip.add(Component.translatable("jade.mmcece.run." + runState.name().toLowerCase(Locale.ROOT)));
        int progress = data.getInt("mmcece:progress_value");
        int duration = data.getInt("mmcece:duration_value");
        int percent = duration <= 0 ? 0 : Math.min(100, progress * 100 / duration);
        tooltip.add(Component.translatable("jade.mmcece.progress", percent + "%"));
      }
      String status = data.getString(RUN_STATUS);
      if (resourceWait(status)) {
        tooltip.add(Component.translatable("jade.mmcece.run_status." + status.toLowerCase(Locale.ROOT)));
      }
    } else {
      tooltip
          .add(Component.translatable("jade.mmcece.run_status." + data.getString(RUN_STATUS).toLowerCase(Locale.ROOT)));
    }
    if (data.contains(PERFORMANCE + "_tick")) {
      tooltip.add(
          Component.translatable(
              "jade.mmcece.performance",
              data.getInt(PERFORMANCE + "_tick"),
              String.format(Locale.ROOT, "%.2f", data.getInt(PERFORMANCE + "_search") / 1000.0)));
      tooltip.add(
          Component.translatable(
              "jade.mmcece.workmode",
              Component.literal(StructureService.workMode()).withStyle(ChatFormatting.GREEN)));
    }
  }

  private static <E extends Enum<E>> E enumValue(Class<E> type, String name) {
    try {
      return Enum.valueOf(type, name);
    } catch (IllegalArgumentException invalid) {
      return null;
    }
  }

  private static boolean resourceWait(String status) {
    return switch (status) {
      case "WAITING_ENERGY", "WAITING_FLUID", "OUTPUT_BLOCKED", "OUTPUT_PLANNING", "MISSING_ITEM_INPUT",
          "MISSING_ITEM_OUTPUT", "MISSING_ENERGY_INPUT", "MISSING_FLUID_INPUT", "MISSING_FLUID_OUTPUT" ->
        true;
      default -> false;
    };
  }
}
