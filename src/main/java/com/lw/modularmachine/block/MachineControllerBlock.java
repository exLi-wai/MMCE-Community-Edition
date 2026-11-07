package com.lw.modularmachine.block;

import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.machine.controller.DedicatedControllerCatalog.Entry;
import com.lw.modularmachine.machine.controller.DedicatedControllerCatalog;
import com.lw.modularmachine.machine.orientation.ControllerOrientationActions;
import com.lw.modularmachine.machine.orientation.ControllerPlacementData;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.registry.MachineRegistries;
import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.CollisionContext;

public final class MachineControllerBlock extends BaseEntityBlock {
  public static final MapCodec<MachineControllerBlock> CODEC = simpleCodec(MachineControllerBlock::new);
  public static final DirectionProperty FACING = BlockStateProperties.FACING;
  public static final IntegerProperty ROLL = IntegerProperty.create("roll", 0, 3);
  public static final BooleanProperty FORMED = BooleanProperty.create("formed");

  private final Entry dedicated;
  public MachineControllerBlock(Properties properties) {
    this(properties, null);
  }
  public MachineControllerBlock(Properties properties, Entry dedicated) {
    super(properties);
    this.dedicated = dedicated;
    registerDefaultState(
        stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(ROLL, 0).setValue(FORMED, false));
  }

  public Entry dedicated() {
    return dedicated;
  }

  @Override
  public MutableComponent getName() {
    return dedicated == null ? super.getName() : DedicatedControllerCatalog.displayName(dedicated, getDescriptionId());
  }
  @Override
  protected MapCodec<? extends BaseEntityBlock> codec() {
    return CODEC;
  }

  @Override
  protected RenderShape getRenderShape(BlockState state) {
    return state.getValue(FORMED) ? RenderShape.INVISIBLE : RenderShape.MODEL;
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    builder.add(FACING, ROLL, FORMED);
  }

  @Override
  protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
    return ControllerBlockGeometry.shape(level, pos);
  }

  @Override
  protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
    return ControllerBlockGeometry.shape(level, pos);
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

  public static MachineOrientation orientationOf(BlockState state) {
    return MachineOrientation.of(state.getValue(FACING), state.getValue(ROLL));
  }

  public static BlockState withOrientation(BlockState state, MachineOrientation orientation) {
    return state.setValue(FACING, orientation.front()).setValue(ROLL, orientation.roll());
  }

  @Override
  protected BlockState rotate(BlockState state, Rotation rotation) {
    var orientation = orientationOf(state);

    return withOrientation(
        state,
        MachineOrientation.fromFrontAndUp(rotation.rotate(orientation.front()), rotation.rotate(orientation.up())));
  }

  @Override
  protected BlockState mirror(BlockState state, Mirror mirror) {
    var orientation = orientationOf(state);

    return withOrientation(
        state,
        MachineOrientation.fromFrontAndUp(mirror.mirror(orientation.front()), mirror.mirror(orientation.up())));
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
