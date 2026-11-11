package com.lw.modularmachine.machine.controller;

import com.lw.modularmachine.api.controller.ControllerOwnership;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.factory.UpgradeLifecyclePublisher;
import com.lw.modularmachine.machine.smart.SmartInterfaceEventPublisher;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;

/** Internal placement, persistence queries and legacy security policy. */
@ApiStatus.Internal
public final class ControllerOwnershipService {
  private ControllerOwnershipService() {
  }

  public static Optional<ControllerOwnership> inspect(ServerLevel level, BlockPos position) {
    Objects.requireNonNull(level, "level");
    Objects.requireNonNull(position, "position");
    ServerThreadGuard.require(level, "Controller ownership query");
    if (!level.hasChunkAt(position)
        || !(level.getBlockEntity(position) instanceof ControllerOwnershipSubject controller)
        || controller.structureRemoved()) {
      return Optional.empty();
    }

    return Optional.of(snapshot(level, controller));
  }

  public static ControllerOwnership snapshot(ServerLevel level, ControllerOwnershipSubject controller) {
    ServerThreadGuard.require(level, "Controller ownership snapshot");
    var state = controller.ownershipState();
    var owner = state.ownerId();
    var player = owner.map(id -> level.getServer().getPlayerList().getPlayer(id)).orElse(null);
    Optional<String> name = Optional.empty();
    if (player != null) {
      name = Optional.ofNullable(player.getGameProfile().getName());
    } else if (owner.isPresent() && level.getServer().getProfileCache() != null) {
      name = level.getServer().getProfileCache().get(owner.get()).map(profile -> profile.getName());
    }
    name = name.filter(value -> !value.isBlank() && value.length() <= 16);

    return new ControllerOwnership(state.valid(), owner, name, player != null);
  }

  public static boolean validatePlacement(BlockPlaceContext context) {
    if (!(context.getLevel() instanceof ServerLevel level)) {
      return true;
    }
    ServerThreadGuard.require(level, "Controller ownership placement");
    var component = context.getItemInHand().get(DataComponents.BLOCK_ENTITY_DATA);
    var state = component == null ? ControllerOwnershipState.empty() : ControllerOwnershipState
        .load(component.copyTag());
    var player = context.getPlayer();
    if (!state.valid()) {
      message(player, "message.mmcece.placement_invalid_data");
      return false;
    }
    if (UpgradeLifecyclePublisher.isDispatchingAny() || SmartInterfaceEventPublisher.isDispatching()) {
      return false;
    }
    if (!state.permits(
        player == null ? null : player.getUUID(),
        player != null && player.isCreative(),
        MachineConfig.securitySystemEnabled())) {
      message(player, "message.mmcece.controller_owner_placement_denied");
      return false;
    }

    return true;
  }

  public static void placed(ServerLevel level, BlockPos position, LivingEntity placer) {
    if (placer instanceof Player player
        && level.getBlockEntity(position) instanceof ControllerOwnershipSubject controller) {
      controller.initializePlayerOwner(player.getUUID());
    }
  }

  /** Pick preserves ownership while starting with fresh inventories and runs. */
  public static ItemStack pick(LevelReader level, BlockPos position, BlockState state) {
    if (level instanceof ServerLevel server) {
      ServerThreadGuard.require(server, "Controller ownership pick");
    }
    var stack = new ItemStack(state.getBlock());
    var entity = level.getBlockEntity(position);
    if (entity instanceof ControllerOwnershipSubject controller
        && (controller.ownershipState().ownerId().isPresent() || !controller.ownershipState().valid())) {
      var fresh = entity.getType().create(position, state);
      if (fresh != null) {
        var root = fresh.saveWithoutMetadata(level.registryAccess());
        controller.ownershipState().save(root);
        BlockItem.setBlockEntityData(stack, fresh.getType(), root);
      }
    }

    return stack;
  }

  public static void requireInitialization(ControllerOwnershipSubject controller) {
    if (!(controller.structureLevel() instanceof ServerLevel level)) {
      throw new IllegalStateException("Controller ownership initialized without a server level");
    }
    ServerThreadGuard.require(level, "Controller ownership initialization");
    if (controller.structureRemoved()
        || !level.hasChunkAt(controller.structurePosition())
        || level.getBlockEntity(controller.structurePosition()) != controller
        || UpgradeLifecyclePublisher.isDispatchingAny()
        || SmartInterfaceEventPublisher.isDispatching()) {
      throw new IllegalStateException("Controller ownership initialized outside a live placement");
    }
  }

  private static void message(Player player, String key) {
    if (player != null) {
      player.displayClientMessage(Component.translatable(key), true);
    }
  }

  public static void synchronize(ControllerOwnershipSubject controller) {
    requireInitialization(controller);
    var level = (ServerLevel) controller.structureLevel();
    var position = controller.structurePosition();
    var state = level.getBlockState(position);
    level.sendBlockUpdated(position, state, state, Block.UPDATE_CLIENTS);
  }
}
