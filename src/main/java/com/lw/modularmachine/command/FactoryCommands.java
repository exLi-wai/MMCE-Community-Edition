package com.lw.modularmachine.command;

import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.machine.factory.FactoryThreadState;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Locale;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;

/**
 * Loaded-position, permission-gated entry points for factory channel intent.
 */
public final class FactoryCommands {
  private static final int INFO_THREAD_LIMIT = 16;

  private FactoryCommands() {
  }

  public static LiteralArgumentBuilder<CommandSourceStack> tree() {
    return Commands.literal("factory").requires(source -> source.hasPermission(2)).then(
        Commands.argument("pos", BlockPosArgument.blockPos()).executes(FactoryCommands::info)
            .then(
                Commands.literal("bind")
                    .then(Commands.argument("machine", ResourceLocationArgument.id()).executes(FactoryCommands::bind)))
            .then(
                Commands.literal("preset")
                    .executes(context -> configure(context, FactoryControllerBlockEntity::resetThreadPreset)))
            .then(
                Commands.literal("add").then(
                    Commands.argument("thread", StringArgumentType.word()).executes(context -> add(context, 0)).then(
                        Commands.argument("priority", priority())
                            .executes(context -> add(context, IntegerArgumentType.getInteger(context, "priority"))))))
            .then(
                Commands.literal("remove").then(
                    Commands.argument("thread", StringArgumentType.word())
                        .executes(context -> configure(context, factory -> factory.removeThread(threadId(context))))))
            .then(
                Commands.literal("priority").then(
                    Commands.argument("thread", StringArgumentType.word()).then(
                        Commands.argument("priority", priority()).executes(
                            context -> configure(
                                context,
                                factory -> factory.setThreadPriority(
                                    threadId(context),
                                    IntegerArgumentType.getInteger(context, "priority"))))))));
  }

  private static IntegerArgumentType priority() {
    return IntegerArgumentType.integer(FactoryThreadState.MIN_PRIORITY, FactoryThreadState.MAX_PRIORITY);
  }

  private static String threadId(CommandContext<CommandSourceStack> context) {
    return StringArgumentType.getString(context, "thread");
  }

  private static FactoryControllerBlockEntity factory(CommandContext<CommandSourceStack> context)
      throws CommandSyntaxException {
    var position = BlockPosArgument.getLoadedBlockPos(context, "pos");
    if (context.getSource().getLevel().getBlockEntity(position) instanceof FactoryControllerBlockEntity factory) {
      return factory;
    }
    context.getSource()
        .sendFailure(Component.translatable("commands.mmcece.factory.not_factory", position.toShortString()));

    return null;
  }

  private static int info(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
    var factory = factory(context);
    if (factory == null) {
      return 0;
    }
    var source = context.getSource();
    source.sendSuccess(
        () -> Component.translatable(
            "commands.mmcece.factory.info",
            factory.machineId(),
            factory.threadCount(),
            factory.threadsInitialized(),
            factory.configurationRevision()),
        false);
    for (var thread : factory.scheduler().channels().stream().limit(INFO_THREAD_LIMIT).toList()) {
      Object recipe = thread.lockedRecipe() == null ? Component
          .translatable("commands.mmcece.factory.unlocked") : thread.lockedRecipe();
      source.sendSuccess(
          () -> Component.translatable(
              "commands.mmcece.factory.thread",
              thread.threadId(),
              thread.priority(),
              thread.enabled(),
              thread.parallelLimit(),
              recipe,
              thread.state().name(),
              thread.reason().name()),
          false);
    }
    if (factory.threadCount() > INFO_THREAD_LIMIT) {
      source.sendSuccess(
          () -> Component.translatable("commands.mmcece.factory.more", factory.threadCount() - INFO_THREAD_LIMIT),
          false);
    }

    return 1;
  }

  private static int bind(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
    var id = ResourceLocationArgument.getId(context, "machine");

    return configure(context, factory -> factory.configureMachine(id));
  }

  private static int add(CommandContext<CommandSourceStack> context, int priority) throws CommandSyntaxException {
    return configure(context, factory -> factory.addThread(threadId(context), priority));
  }

  private static int configure(CommandContext<CommandSourceStack> context, Function<FactoryControllerBlockEntity, FactoryControllerBlockEntity.ConfigurationResult> action)
      throws CommandSyntaxException {
    var factory = factory(context);
    if (factory == null) {
      return 0;
    }
    var result = action.apply(factory);
    var message = Component.translatable("commands.mmcece.factory.result." + result.name().toLowerCase(Locale.ROOT));
    if (result == FactoryControllerBlockEntity.ConfigurationResult.SUCCESS) {
      context.getSource().sendSuccess(() -> message, true);
      return 1;
    }
    if (result == FactoryControllerBlockEntity.ConfigurationResult.NO_CHANGE) {
      context.getSource().sendSuccess(() -> message, false);
      return 1;
    }
    context.getSource().sendFailure(message);

    return 0;
  }
}
