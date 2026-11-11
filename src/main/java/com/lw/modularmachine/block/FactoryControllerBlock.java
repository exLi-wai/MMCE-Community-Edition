package com.lw.modularmachine.block;

import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.machine.appearance.ControllerAppearance;
import com.lw.modularmachine.machine.appearance.ControllerAppearanceState;
import com.lw.modularmachine.machine.controller.DedicatedControllerCatalog.Entry;
import com.lw.modularmachine.machine.controller.DedicatedControllerCatalog;
import com.lw.modularmachine.machine.controller.ControllerOwnershipService;
import com.lw.modularmachine.machine.orientation.ControllerOrientationActions;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.registry.MachineRegistries;
import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.storage.loot.LootParams.Builder;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.NotNull;

public final class FactoryControllerBlock extends BaseEntityBlock {
  public static final MapCodec<FactoryControllerBlock> CODEC = simpleCodec(FactoryControllerBlock::new);
  public static final DirectionProperty FACING = BlockStateProperties.FACING;
  public static final IntegerProperty ROLL = IntegerProperty.create("roll", 0, 3);
  public static final BooleanProperty FORMED = ControllerAppearance.FORMED;
  public static final BooleanProperty HIDDEN = ControllerAppearance.HIDDEN;
  public static final EnumProperty<ControllerAppearanceState> APPEARANCE = ControllerAppearance.APPEARANCE;

  private final Entry dedicated;
  public FactoryControllerBlock(Properties properties) {
    this(properties, null);
  }
  public FactoryControllerBlock(Properties properties, Entry dedicated) {
    super(properties);
    this.dedicated = dedicated;
    registerDefaultState(
        stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(ROLL, 0).setValue(FORMED, false)
            .setValue(HIDDEN, false).setValue(APPEARANCE, ControllerAppearanceState.UNFORMED));
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
    return state.getValue(HIDDEN) ? RenderShape.INVISIBLE : RenderShape.MODEL;
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    builder.add(FACING, ROLL, FORMED, HIDDEN, APPEARANCE);
  }

  @Override
  protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
    return ControllerBlockGeometry.shape(level, pos);
  }

  @Override
  protected @NotNull VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
    return ControllerBlockGeometry.shape(level, pos);
  }

  @Override
  public BlockState getStateForPlacement(@NotNull BlockPlaceContext context) {
    if (dedicated != null && !context.getLevel().isClientSide) {
      var definition = StructureService.definition(dedicated.machine());
      if (definition == null) {
        return null;
      }
      var selected = ControllerOrientationActions.placement(
          context.getNearestLookingDirection(),
          context.getHorizontalDirection(),
          definition.allowedOrientationMask());
      return selected.map(machineOrientation -> withOrientation(defaultBlockState(), machineOrientation)).orElse(null);
    }
    return withOrientation(
        defaultBlockState(),
        ControllerOrientationActions
            .preferredPlacement(context.getNearestLookingDirection(), context.getHorizontalDirection()));
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
    return new FactoryControllerBlockEntity(pos, state);
  }

  @Override
  public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
    super.setPlacedBy(level, pos, state, placer, stack);
    if (level instanceof ServerLevel server) {
      ControllerOwnershipService.placed(server, pos, placer);
    }
  }

  @Override
  public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
    return ControllerOwnershipService.pick(level, pos, state);
  }

  @Override
  public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
    return level.isClientSide ? null : createTickerHelper(
        type,
        MachineRegistries.FACTORY_CONTROLLER_ENTITY.get(),
        (world, pos, blockState, factory) -> factory.serverTick());
  }

  @Override
  protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
    if (!level.isClientSide
        && level.getBlockEntity(pos) instanceof FactoryControllerBlockEntity factory
        && player instanceof ServerPlayer serverPlayer) {
      serverPlayer.openMenu(factory, pos);
    }
    return InteractionResult.sidedSuccess(level.isClientSide);
  }
  @Override
  protected List<ItemStack> getDrops(BlockState state, Builder params) {
    var drops = super.getDrops(state, params);
    if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof FactoryControllerBlockEntity factory) {
      for (var stack : drops) {
        if (stack.is(asItem())) {
          BlockItem
              .setBlockEntityData(stack, factory.getType(), factory.portableData(params.getLevel().registryAccess()));
        }
      }
    }
    return drops;
  }
}
