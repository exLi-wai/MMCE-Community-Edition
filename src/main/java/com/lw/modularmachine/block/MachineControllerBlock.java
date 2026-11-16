package com.lw.modularmachine.block;

import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.machine.controller.DedicatedControllerCatalog.Entry;
import com.lw.modularmachine.machine.orientation.ControllerOrientationActions;
import com.lw.modularmachine.machine.orientation.ControllerPlacementData;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.registry.MachineRegistries;
import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;

public final class MachineControllerBlock extends ControllerBlock {
  public static final MapCodec<MachineControllerBlock> CODEC = simpleCodec(MachineControllerBlock::new);
  public static final IntegerProperty ROLL = IntegerProperty.create("roll", 0, 3);

  @Override
  protected IntegerProperty rollProperty() {
    return ROLL;
  }

  public static MachineOrientation orientationOf(BlockState state) {
    return ControllerBlock.orientationOf(state, ROLL);
  }

  public static BlockState withOrientation(BlockState state, MachineOrientation orientation) {
    return ControllerBlock.withOrientation(state, orientation, ROLL);
  }
  public MachineControllerBlock(Properties properties) {
    this(properties, null);
  }
  public MachineControllerBlock(Properties properties, Entry dedicated) {
    super(properties, dedicated);
  }

  @Override
  protected MapCodec<? extends BaseEntityBlock> codec() {
    return CODEC;
  }

  @Override
  public BlockState getStateForPlacement(BlockPlaceContext context) {
    var binding = dedicated == null ? ControllerPlacementData
        .fromStack(context.getItemInHand()) : new ControllerPlacementData(dedicated.machine(), false);
    var horizontal = context.getHorizontalDirection();
    var fallback = MachineOrientation.of(horizontal.getOpposite(), 0);
    if (context.getLevel().isClientSide) {
      var preferred = !binding.opaque() ? ControllerOrientationActions
          .preferredPlacement(context.getNearestLookingDirection(), horizontal) : fallback;
      return withOrientation(defaultBlockState(), preferred);
    }
    var definition = binding.opaque() ? null : StructureService.definition(binding.machineId());
    if (definition == null) {
      var preferred = binding.opaque()
          || !ControllerPlacementData.UNBOUND_MACHINE
              .equals(binding.machineId()) ? fallback : ControllerOrientationActions
                  .preferredPlacement(context.getNearestLookingDirection(), horizontal);
      return withOrientation(defaultBlockState(), preferred);
    }
    var selected = ControllerOrientationActions
        .placement(context.getNearestLookingDirection(), horizontal, definition.allowedOrientationMask());
    if (selected.isEmpty()) {
      if (context.getPlayer() != null) {
        context.getPlayer().displayClientMessage(Component.translatable("message.mmcece.placement_not_allowed"), true);
      }
      return null;
    }
    return withOrientation(defaultBlockState(), selected.get());
  }

  @Override
  public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
    return new MachineControllerBlockEntity(pos, state);
  }

  @Override
  public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
    return level.isClientSide ? null : createTickerHelper(
        type,
        MachineRegistries.CONTROLLER_ENTITY.get(),
        (world, pos, blockState, controller) -> controller.serverTick());
  }

  @Override
  protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
    if (!level.isClientSide && level.getBlockEntity(pos) instanceof MachineControllerBlockEntity controller) {
      if (player instanceof ServerPlayer serverPlayer) {
        serverPlayer.openMenu(controller, pos);
      }
    }

    return InteractionResult.sidedSuccess(level.isClientSide);
  }

  @Override
  protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
    List<ItemStack> drops = super.getDrops(state, params);
    if (params
        .getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof MachineControllerBlockEntity controller) {
      for (ItemStack stack : drops) {
        if (stack.is(asItem())) {
          BlockItem.setBlockEntityData(
              stack,
              controller.getType(),
              controller.portableData(params.getLevel().registryAccess()));
        }
      }
    }

    return drops;
  }
}
