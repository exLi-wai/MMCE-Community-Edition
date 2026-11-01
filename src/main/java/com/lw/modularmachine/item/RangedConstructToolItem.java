package com.lw.modularmachine.item;

import com.lw.modularmachine.block.FactoryControllerBlock;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.machine.orientation.MachineOrientation;
import com.lw.modularmachine.machine.selection.RangedSelectionService;
import com.lw.modularmachine.registry.MachineDataComponents;
import java.io.IOException;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Two-corner range selection that exports the enclosed blocks as a schema 2
 * machine definition, relative to the controller the player right-clicks last.
 */
public final class RangedConstructToolItem extends Item {
  public RangedConstructToolItem(Properties properties) {
    super(properties);
  }

  @Override
  public InteractionResult useOn(UseOnContext context) {
    var player = context.getPlayer();
    if (player == null) {
      return InteractionResult.PASS;
    }
    return interact(context.getLevel(), player, context.getItemInHand(), context.getClickedPos(), context.getHand());
  }

  /**
   * Shared by {@link #useOn} and the interaction event; controllers open their
   * own GUI before an item's useOn, so the event path is the real entry.
   */
  public static InteractionResult interact(Level level, Player player, ItemStack stack, BlockPos pos, InteractionHand hand) {
    var clicked = pos.immutable();
    if (level.isClientSide) {
      return InteractionResult.SUCCESS;
    }
    if (player.isSecondaryUseActive()) {
      stack.remove(MachineDataComponents.SELECTION_FIRST.get());
      stack.remove(MachineDataComponents.SELECTION_SECOND.get());
      player.containerMenu.broadcastChanges();
      player.displayClientMessage(Component.translatable("message.mmcece.selection.cleared"), true);
      return InteractionResult.SUCCESS;
    }
    if (!player.hasPermissions(2) && !player.isCreative()) {
      player.displayClientMessage(Component.translatable("message.mmcece.selection.permission"), true);
      return InteractionResult.FAIL;
    }
    var first = stack.get(MachineDataComponents.SELECTION_FIRST.get());
    var second = stack.get(MachineDataComponents.SELECTION_SECOND.get());
    var state = level.getBlockState(clicked);
    if (isController(state) && first != null && second != null) {
      if (player instanceof ServerPlayer server && level instanceof ServerLevel serverLevel) {
        export(serverLevel, clicked, state, first, second, server);
      }
      return InteractionResult.SUCCESS;
    }
    if (first == null) {
      stack.set(MachineDataComponents.SELECTION_FIRST.get(), clicked);
      player.containerMenu.broadcastChanges();
      player.displayClientMessage(Component.translatable("message.mmcece.selection.first", coordinates(clicked)), true);
      return InteractionResult.SUCCESS;
    }
    if (!first.equals(clicked)) {
      stack.set(MachineDataComponents.SELECTION_SECOND.get(), clicked);
      player.containerMenu.broadcastChanges();
      player.displayClientMessage(
          Component.translatable("message.mmcece.selection.second", coordinates(clicked), coordinates(first)),
          true);
      return InteractionResult.SUCCESS;
    }
    stack.remove(MachineDataComponents.SELECTION_SECOND.get());
    player.containerMenu.broadcastChanges();
    player.displayClientMessage(Component.translatable("message.mmcece.selection.same_corner"), true);
    return InteractionResult.SUCCESS;
  }

  @Override
  public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
    lines.add(Component.translatable("tooltip.mmcece.ranged_construct_tool"));
    var first = stack.get(MachineDataComponents.SELECTION_FIRST.get());
    if (first != null) {
      lines.add(Component.translatable("tooltip.mmcece.ranged_construct_tool.first", coordinates(first)));
    }
    var second = stack.get(MachineDataComponents.SELECTION_SECOND.get());
    if (second != null) {
      lines.add(Component.translatable("tooltip.mmcece.ranged_construct_tool.second", coordinates(second)));
    }
  }

  private static void export(ServerLevel level, BlockPos controller, BlockState state, BlockPos first, BlockPos second, ServerPlayer player) {
    MachineOrientation orientation = state.getBlock() instanceof FactoryControllerBlock ? FactoryControllerBlock
        .orientationOf(state) : MachineControllerBlock.orientationOf(state);
    try {
      var result = RangedSelectionService
          .export(level, controller, orientation, first, second, player.getGameProfile().getName());
      player.displayClientMessage(
          Component
              .translatable("message.mmcece.selection.saved", result.file().getFileName().toString(), result.parts()),
          false);
      player.displayClientMessage(
          Component.translatable("message.mmcece.selection.reload", result.machine().toString()),
          false);
    } catch (IllegalArgumentException invalid) {
      player
          .displayClientMessage(Component.translatable("message.mmcece.selection.invalid", invalid.getMessage()), true);
    } catch (IOException failure) {
      player
          .displayClientMessage(Component.translatable("message.mmcece.selection.failed", failure.getMessage()), true);
    }
  }

  private static Component coordinates(BlockPos pos) {
    return Component.literal(pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
  }

  private static boolean isController(BlockState state) {
    return state.getBlock() instanceof MachineControllerBlock || state.getBlock() instanceof FactoryControllerBlock;
  }
}
