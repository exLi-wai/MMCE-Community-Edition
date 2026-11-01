package com.lw.modularmachine.item;

import com.lw.modularmachine.machine.blueprint.BlueprintPreviewActions;
import com.lw.modularmachine.machine.blueprint.MachineBlueprint;
import com.lw.modularmachine.machine.controller.DedicatedControllerCatalog;
import com.lw.modularmachine.machine.orientation.MachineOrientation;
import com.lw.modularmachine.registry.MachineDataComponents;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * Registered identity with the original blueprint right-click preview
 * interaction.
 */
public final class MachineBlueprintItem extends Item {
  private final DedicatedControllerCatalog.Machine machine;
  public MachineBlueprintItem(Properties properties, DedicatedControllerCatalog.Machine machine) {
    super(properties);
    this.machine = machine;
  }
  public ResourceLocation machineId() {
    return machine.id();
  }
  public static MachineBlueprint.Binding binding(ItemStack stack) {
    if (!(stack.getItem() instanceof MachineBlueprintItem item)) {
      return null;
    }
    var pose = stack
        .getOrDefault(MachineDataComponents.BLUEPRINT.get(), MachineBlueprint.of(MachineOrientation.IDENTITY));
    return new MachineBlueprint.Binding(item.machineId(), pose.orientation());
  }
  @Override
  public Component getName(ItemStack stack) {
    return Component.translatable("item.mmcece.registered_blueprint", Component.translatable(machine.translationKey()));
  }
  @Override
  public InteractionResult useOn(UseOnContext context) {
    if (context.getLevel().isClientSide) {
      BlueprintPreviewActions.openClient(machineId(), context.getHand());
    }
    return InteractionResult.SUCCESS;
  }
  @Override
  public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
    if (level.isClientSide) {
      BlueprintPreviewActions.openClient(machineId(), hand);
    }
    return InteractionResultHolder.pass(player.getItemInHand(hand));
  }
  @Override
  public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
    lines.add(Component.translatable("tooltip.mmcece.registered_blueprint"));
    lines.add(Component.translatable(machine.translationKey()));
  }
}
