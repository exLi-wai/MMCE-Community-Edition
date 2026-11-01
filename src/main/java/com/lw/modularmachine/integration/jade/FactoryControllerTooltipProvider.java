package com.lw.modularmachine.integration.jade;

import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.machine.structure.StructureService;
import java.util.Locale;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

final class FactoryControllerTooltipProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
  static final FactoryControllerTooltipProvider INSTANCE = new FactoryControllerTooltipProvider();
  private static final String PREFIX = "mmcece:factory_";

  private FactoryControllerTooltipProvider() {
  }

  @Override
  public ResourceLocation getUid() {
    return ResourceLocation.fromNamespaceAndPath("mmcece", "factory_status");
  }

  @Override
  public boolean shouldRequestData(BlockAccessor accessor) {
    return accessor.getBlockEntity() instanceof FactoryControllerBlockEntity;
  }

  @Override
  public void appendServerData(CompoundTag data, BlockAccessor accessor) {
    if (!(accessor.getBlockEntity() instanceof FactoryControllerBlockEntity factory)) {
      return;
    }
    data.putString(PREFIX + "structure", factory.verificationState().name());
    data.putString(PREFIX + "failure", factory.verificationFailure().name());
    data.putLong(PREFIX + "epoch", StructureService.definitionEpoch());
    var threads = factory.scheduler().channels();
    int count = Math.min(threads.size(), 8);
    data.putInt(PREFIX + "count", count);
    for (int i = 0; i < count; i++) {
      var thread = threads.get(i);
      String key = PREFIX + i + "_";
      data.putString(key + "id", thread.threadId());
      data.putString(key + "state", thread.state().name());
      data.putString(key + "reason", thread.reason().name());
      data.putInt(key + "parallel", thread.parallelLimit());
      var upgrade = thread.upgradeSnapshot();
      data.putInt(key + "speed_num", upgrade.speedNumerator());
      data.putInt(key + "speed_den", upgrade.speedDenominator());
      data.putInt(key + "energy_num", upgrade.energyNumerator());
      data.putInt(key + "energy_den", upgrade.energyDenominator());
      data.putInt(key + "fluid_num", upgrade.fluidNumerator());
      data.putInt(key + "fluid_den", upgrade.fluidDenominator());
      factory.run(thread.threadId()).ifPresentOrElse(run -> {
        data.putBoolean(key + "has_run", true);
        data.putString(key + "recipe", run.recipeId().toString());
        data.putString(key + "run_state", run.state().name());
        data.putInt(key + "progress", run.progress());
        data.putInt(key + "duration", run.duration());
      }, () -> data.putBoolean(key + "has_run", false));
    }
  }

  @Override
  public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
    var data = accessor.getServerData();
    if (!data.contains(PREFIX + "structure")) {
      return;
    }
    String structure = data.getString(PREFIX + "structure").toLowerCase(Locale.ROOT);
    tooltip.add(Component.translatable("jade.mmcece.factory.structure." + structure));
    tooltip.add(Component.translatable("jade.mmcece.factory.epoch", data.getLong(PREFIX + "epoch")));
    int count = Math.min(data.getInt(PREFIX + "count"), 8);
    for (int i = 0; i < count; i++) {
      String key = PREFIX + i + "_";
      tooltip.add(
          Component.translatable(
              "jade.mmcece.factory.thread",
              data.getString(key + "id"),
              Component.translatable(
                  "menu.mmcece.factory.thread_state." + data.getString(key + "state").toLowerCase(Locale.ROOT))));
      tooltip.add(Component.translatable("jade.mmcece.factory.parallel", data.getInt(key + "parallel")));
      tooltip.add(
          Component.translatable(
              "jade.mmcece.factory.upgrades",
              data.getInt(key + "speed_num"),
              data.getInt(key + "speed_den"),
              data.getInt(key + "energy_num"),
              data.getInt(key + "energy_den"),
              data.getInt(key + "fluid_num"),
              data.getInt(key + "fluid_den")));
      String reason = data.getString(key + "reason");
      if (!"NONE".equals(reason)) {
        tooltip.add(
            Component.translatable(
                "jade.mmcece.factory.reason",
                Component.translatable("menu.mmcece.factory.reason." + reason.toLowerCase(Locale.ROOT))));
      }
      if (data.getBoolean(key + "has_run")) {
        tooltip.add(
            Component.translatable(
                "jade.mmcece.factory.progress",
                data.getInt(key + "progress"),
                data.getInt(key + "duration")));
      }
    }
  }
}
