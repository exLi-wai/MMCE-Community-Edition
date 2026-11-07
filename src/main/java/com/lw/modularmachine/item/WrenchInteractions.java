package com.lw.modularmachine.item;

import com.lw.modularmachine.api.controller.MachineControllerApi;
import com.lw.modularmachine.block.FactoryControllerBlock;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.machine.orientation.ControllerOrientationActions;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.structure.StructureService.StructureSubject;
import java.util.Locale;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Normal right-click packets only: no second custom request, Item.useOn
 * fallback, or duplicate hand mutation.
 */
public final class WrenchInteractions {
  public static final TagKey<Item> WRENCHES = TagKey
      .create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("mmcece", "controller_wrenches"));

  private WrenchInteractions() {
  }

  public static boolean isWrench(ItemStack stack) {
    return !stack.isEmpty() && (stack.getItem() instanceof MachineWrenchItem || stack.is(WRENCHES));
  }

  public static Component direction(Direction direction) {
    return Component.translatable("direction.mmcece." + direction.getSerializedName());
  }

  public static Component pose(MachineOrientation orientation) {
    return Component.translatable(
        "message.mmcece.orientation",
        direction(orientation.front()),
        direction(orientation.up()),
        orientation.roll() * 90);
  }

  @SubscribeEvent(priority = EventPriority.LOWEST)
  public static void rightClick(PlayerInteractEvent.RightClickBlock event) {
    if (event.isCanceled() || !isWrench(event.getItemStack())) {
      return;
    }
    var level = event.getLevel();
    if (!level.hasChunkAt(event.getPos()) || !isController(level.getBlockState(event.getPos()))) {
      return;
    }
    // Respect earlier protection hooks. Never let a denied block action fall
    // through to an item's
    // own use.
    if (event.getUseBlock().isFalse() || event.getUseItem().isFalse()) {
      event.setCanceled(true);
      event.setCancellationResult(InteractionResult.FAIL);
      return;
    }
    event.setCanceled(true);
    event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
    if (level.isClientSide) {
      return;
    }
    if (!(event.getEntity() instanceof ServerPlayer player)) {
      event.setCancellationResult(InteractionResult.FAIL);
      return;
    }
    if (event.getHand() != InteractionHand.MAIN_HAND) {
      player.displayClientMessage(Component.translatable("message.mmcece.wrench_main_hand"), true);
      return;
    }
    // Query the actual held stack and world instance; never trust client preview
    // data.
    if (!isWrench(player.getMainHandItem())
        || !(level.getBlockEntity(event.getPos()) instanceof StructureSubject controller)) {
      event.setCancellationResult(InteractionResult.FAIL);
      return;
    }
    var state = level.getBlockState(event.getPos());
    var current = state.getBlock() instanceof FactoryControllerBlock ? FactoryControllerBlock
        .orientationOf(state) : MachineControllerBlock.orientationOf(state);
    var target = ControllerOrientationActions
        .wrenchTarget(current, event.getHitVec().getDirection(), player.isSecondaryUseActive());
    var result = MachineControllerApi.reorient(controller, target, player);
    player.displayClientMessage(
        Component.translatable("message.mmcece.wrench_" + result.name().toLowerCase(Locale.ROOT), pose(target)),
        true);
  }

  private static boolean isController(BlockState state) {
    return state.getBlock() instanceof MachineControllerBlock || state.getBlock() instanceof FactoryControllerBlock;
  }
}
