package com.lw.modularmachine.command;

import com.lw.modularmachine.machine.assembly.AssemblyService;
import com.lw.modularmachine.machine.blueprint.MachineBlueprint;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.registry.MachineDataComponents;
import com.lw.modularmachine.registry.MachineRegistries;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;

/**
 * Explicit administrative bootstrap for definitions without an already-bound
 * controller.
 */
final class BlueprintCommands {
  private BlueprintCommands() {
  }
  static LiteralArgumentBuilder<CommandSourceStack> tree() {
    return Commands.literal("blueprint").then(
        Commands.argument("machine", ResourceLocationArgument.id())
            .suggests(
                (context, builder) -> SharedSuggestionProvider
                    .suggestResource(StructureService.definitionsSnapshot().keySet(), builder))
            .executes(context -> {
              var player = context.getSource().getPlayerOrException();
              if (!AssemblyService.canEdit(player)) {
                return 0;
              }
              var machine = ResourceLocationArgument.getId(context, "machine");
              var definition = StructureService.definition(machine);
              if (definition == null) {
                context.getSource().sendFailure(Component.translatable("message.mmcece.blueprint.invalid"));
                return 0;
              }
              var pose = MachineOrientation.all().stream().filter(definition::allows).findFirst().orElseThrow();
              var registered = MachineRegistries.BLUEPRINTS.get(machine);
              if (registered == null) {
                context.getSource().sendFailure(Component.translatable("commands.mmcece.controllers.restart_required"));
                return 0;
              }
              var stack = registered.get().getDefaultInstance();
              stack.set(MachineDataComponents.BLUEPRINT.get(), MachineBlueprint.of(pose));
              if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
              }
              player.inventoryMenu.broadcastChanges();
              context.getSource().sendSuccess(
                  () -> Component.translatable("message.mmcece.blueprint.bound", machine.toString()),
                  false);
              return 1;
            }));
  }
}
