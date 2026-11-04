package com.lw.modularmachine.command;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.MachineControllerApi;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.controller.DedicatedControllerCatalog;
import com.lw.modularmachine.machine.structure.StructureService;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class MachineCommands {
  private MachineCommands() {
  }

  @SubscribeEvent
  public static void register(RegisterCommandsEvent event) {
    register(event.getDispatcher());
  }

  static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    dispatcher.register(
        Commands.literal(ModularMachine.MODID).requires(source -> source.hasPermission(2))
            .then(
                Commands.literal("diagnose").then(
                    Commands.argument("pos", BlockPosArgument.blockPos()).executes(
                        context -> diagnose(context.getSource(), BlockPosArgument.getLoadedBlockPos(context, "pos")))))
            .then(
                Commands.literal("performance").then(
                    Commands.argument("pos", BlockPosArgument.blockPos()).executes(
                        context -> performance(
                            context.getSource(),
                            BlockPosArgument.getLoadedBlockPos(context, "pos")))))
            .then(BlueprintCommands.tree()).then(FactoryCommands.tree())
            .then(Commands.literal("held").executes(context -> held(context.getSource())))
            .then(Commands.literal("reload").executes(context -> reload(context.getSource()))));
  }

  private static int diagnose(CommandSourceStack source, BlockPos position) {
    ServerLevel level = source.getLevel();
    if (!(level.getBlockEntity(position) instanceof MachineControllerBlockEntity controller)) {
      source.sendFailure(Component.translatable("commands.mmcece.diagnose.not_controller", position.toShortString()));
      return 0;
    }
    var snapshot = MachineControllerApi.inspect(controller);
    if (snapshot.isEmpty()) {
      source.sendFailure(Component.translatable("commands.mmcece.diagnose.unavailable"));
      return 0;
    }
    var value = snapshot.orElseThrow();
    source.sendSuccess(
        () -> Component.translatable(
            "commands.mmcece.diagnose.header",
            value.machineId(),
            value.structureState().name(),
            value.structureFailure().name()),
        false);
    source.sendSuccess(
        () -> Component.translatable(
            "commands.mmcece.diagnose.orientation",
            value.orientation().front().getSerializedName(),
            value.orientation().roll(),
            value.definitionEpoch()),
        false);
    source.sendSuccess(
        () -> Component.translatable(
            "commands.mmcece.diagnose.production",
            value.productionAllowed(),
            value.recipeRunStatus().name()),
        false);
    value.structureDiagnostic().ifPresent(
        diagnostic -> source.sendSuccess(
            () -> Component.translatable(
                "commands.mmcece.diagnose.structure_detail",
                diagnostic.partId(),
                diagnostic.position().toShortString(),
                MachineControllerApi.localizedDiagnostic(diagnostic.expected()),
                MachineControllerApi.localizedDiagnostic(diagnostic.actual())),
            false));
    value.recipeInputDiagnostic().ifPresent(diagnostic -> {
      source.sendSuccess(() -> Component.translatable("commands.mmcece.diagnose.recipe", diagnostic.recipeId()), false);
      if (diagnostic.shortages().isEmpty()) {
        source.sendSuccess(() -> Component.translatable("commands.mmcece.diagnose.inputs_ready"), false);
      } else {
        for (var shortage : diagnostic.shortages()) {
          source.sendSuccess(
              () -> Component.translatable(
                  "commands.mmcece.diagnose.input_shortage",
                  shortage.missing(),
                  shortage.key(),
                  shortage.available(),
                  shortage.required()),
              false);
        }
      }
    });
    return 1;
  }

  private static int performance(CommandSourceStack source, BlockPos position) {
    if (!(source.getLevel().getBlockEntity(position) instanceof MachineControllerBlockEntity controller)) {
      source.sendFailure(Component.translatable("commands.mmcece.diagnose.not_controller", position.toShortString()));
      return 0;
    }
    var snapshot = MachineControllerApi.inspect(controller);
    if (snapshot.isEmpty()) {
      source.sendFailure(Component.translatable("commands.mmcece.diagnose.unavailable"));
      return 0;
    }
    var value = snapshot.orElseThrow();
    source.sendSuccess(
        () -> Component.translatable(
            "commands.mmcece.performance.telemetry",
            StructureService.pendingWorkCount(controller),
            value.successfulTransfers(),
            value.bindingRebuilds(),
            controller.conservativeChecks()),
        false);
    source.sendSuccess(
        () -> Component.translatable("commands.mmcece.performance.budget", MachineConfig.RECIPE_OUTPUT_WORK.get()),
        false);
    return 1;
  }

  private static int held(CommandSourceStack source) {
    if (!(source.getEntity() instanceof ServerPlayer player)) {
      source.sendFailure(Component.translatable("commands.mmcece.held.player_only"));
      return 0;
    }
    var stack = player.getMainHandItem();
    if (stack.isEmpty()) {
      source.sendSuccess(() -> Component.translatable("commands.mmcece.held.empty"), false);
      return 1;
    }
    source.sendSuccess(
        () -> Component.translatable(
            "commands.mmcece.held.item",
            BuiltInRegistries.ITEM.getKey(stack.getItem()),
            stack.getHoverName(),
            stack.getCount()),
        false);
    return 1;
  }

  private static int reload(CommandSourceStack source) {
    if (DedicatedControllerCatalog.restartRequired()) {
      source.sendSuccess(() -> Component.translatable("commands.mmcece.controllers.restart_required"), false);
    }
    var server = source.getServer();
    source.sendSuccess(() -> Component.translatable("commands.mmcece.reload.started"), false);
    server.reloadResources(server.getPackRepository().getSelectedIds())
        .whenComplete((ignored, failure) -> server.execute(() -> {
          if (failure == null) {
            source.sendSuccess(() -> Component.translatable("commands.mmcece.reload.success"), false);
          } else {
            source.sendFailure(Component.translatable("commands.mmcece.reload.failed", failure.getMessage()));
          }
        }));
    return 1;
  }
}
