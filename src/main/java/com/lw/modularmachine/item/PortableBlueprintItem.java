package com.lw.modularmachine.item;

import com.lw.modularmachine.block.FactoryControllerBlock;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.machine.assembly.AssemblyService;
import com.lw.modularmachine.machine.blueprint.BlueprintPreviewActions;
import com.lw.modularmachine.machine.blueprint.MachineBlueprint;
import com.lw.modularmachine.machine.controller.DedicatedControllerCatalog;
import com.lw.modularmachine.machine.orientation.MachineOrientation;
import com.lw.modularmachine.registry.MachineDataComponents;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Original single blueprint item kept alongside the per-machine registered
 * blueprints. Machine identity lives in the component, exactly like the legacy
 * NBT {@code dynamicmachine} tag; registered blueprints remain authoritative
 * for controller slots.
 */
public final class PortableBlueprintItem extends Item {
  public PortableBlueprintItem(Properties properties) {
    super(properties);
  }

  public static ResourceLocation machine(ItemStack stack) {
    return stack.getItem() instanceof PortableBlueprintItem ? stack
        .get(MachineDataComponents.BLUEPRINT_MACHINE.get()) : null;
  }

  public static MachineOrientation pose(ItemStack stack) {
    var value = stack.get(MachineDataComponents.BLUEPRINT.get());
    return value == null ? MachineOrientation.IDENTITY : value.orientation();
  }

  public static void bind(ItemStack stack, ResourceLocation machine, MachineOrientation orientation) {
    stack.set(MachineDataComponents.BLUEPRINT_MACHINE.get(), machine);
    stack.set(MachineDataComponents.BLUEPRINT.get(), MachineBlueprint.of(orientation));
  }

  public static ItemStack bound(Item item, ResourceLocation machine, MachineOrientation orientation) {
    var stack = item.getDefaultInstance();
    bind(stack, machine, orientation);
    return stack;
  }

  @Override
  public Component getName(ItemStack stack) {
    return Component.translatable(getDescriptionId());
  }

  @Override
  public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
    lines.add(Component.translatable("tooltip.mmcece.blueprint"));
    var machine = machine(stack);
    if (machine == null) {
      lines.add(Component.translatable("tooltip.mmcece.blueprint.empty"));
    } else {
      lines.add(
          Component.translatable(
              "tooltip.mmcece.blueprint.machine",
              Component.translatable(DedicatedControllerCatalog.translation(machine))));
    }
  }

  @Override
  public InteractionResult useOn(UseOnContext context) {
    var stack = context.getItemInHand();
    var level = context.getLevel();
    var pos = context.getClickedPos();
    var state = level.getBlockState(pos);
    var machine = controllerMachine(level, pos, state);
    if (machine == null) {
      if (level.isClientSide) {
        var bound = machine(stack);
        if (bound != null) {
          BlueprintPreviewActions.openClient(bound, context.getHand());
          return InteractionResult.SUCCESS;
        }
      }
      return InteractionResult.PASS;
    }
    if (level.isClientSide) {
      return InteractionResult.SUCCESS;
    }
    var player = context.getPlayer();
    if (player == null) {
      return InteractionResult.PASS;
    }
    if (!(player instanceof ServerPlayer server) || !AssemblyService.canEdit(server)) {
      return InteractionResult.FAIL;
    }
    bind(stack, machine, pose(state));
    player.displayClientMessage(
        Component.translatable(
            "message.mmcece.blueprint.copied",
            Component.translatable(DedicatedControllerCatalog.translation(machine))),
        true);
    return InteractionResult.SUCCESS;
  }

  @Override
  public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
    var stack = player.getItemInHand(hand);
    var machine = machine(stack);
    if (level.isClientSide && machine != null) {
      BlueprintPreviewActions.openClient(machine, hand);
      return InteractionResultHolder.success(stack);
    }
    return InteractionResultHolder.pass(stack);
  }

  private static ResourceLocation controllerMachine(Level level, BlockPos pos, BlockState state) {
    if (state.getBlock() instanceof FactoryControllerBlock) {
      return level.getBlockEntity(pos) instanceof FactoryControllerBlockEntity factory ? factory.machineId() : null;
    }
    if (state.getBlock() instanceof MachineControllerBlock) {
      return level.getBlockEntity(pos) instanceof MachineControllerBlockEntity controller ? controller
          .machineId() : null;
    }
    return null;
  }

  private static MachineOrientation pose(BlockState state) {
    if (state.getBlock() instanceof FactoryControllerBlock) {
      return MachineOrientation
          .of(state.getValue(FactoryControllerBlock.FACING), state.getValue(FactoryControllerBlock.ROLL));
    }
    return MachineControllerBlock.orientationOf(state);
  }
}
