package com.lw.modularmachine.block;

import com.lw.modularmachine.machine.appearance.ControllerAppearance;
import com.lw.modularmachine.machine.appearance.ControllerAppearanceState;
import com.lw.modularmachine.machine.controller.DedicatedControllerCatalog.Entry;
import com.lw.modularmachine.machine.controller.DedicatedControllerCatalog;
import com.lw.modularmachine.machine.controller.ControllerOwnershipService;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.ApiStatus;

/**
 * Shared controller block appearance, geometry, orientation and ownership
 * hooks.
 */
@ApiStatus.Internal
abstract class ControllerBlock extends BaseEntityBlock {
  public static final DirectionProperty FACING = BlockStateProperties.FACING;
  public static final BooleanProperty FORMED = ControllerAppearance.FORMED;
  public static final BooleanProperty HIDDEN = ControllerAppearance.HIDDEN;
  public static final EnumProperty<ControllerAppearanceState> APPEARANCE = ControllerAppearance.APPEARANCE;

  protected final Entry dedicated;

  ControllerBlock(Properties properties, Entry dedicated) {
    super(properties);
    this.dedicated = dedicated;
    registerDefaultState(
        stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(rollProperty(), 0).setValue(FORMED, false)
            .setValue(HIDDEN, false).setValue(APPEARANCE, ControllerAppearanceState.UNFORMED));
  }

  /**
   * Each controller role retains its original property identity. Called during
   * Block construction.
   */
  protected abstract IntegerProperty rollProperty();

  public Entry dedicated() {
    return dedicated;
  }

  @Override
  public MutableComponent getName() {
    return dedicated == null ? super.getName() : DedicatedControllerCatalog.displayName(dedicated, getDescriptionId());
  }

  @Override
  protected RenderShape getRenderShape(BlockState state) {
    return state.getValue(HIDDEN) ? RenderShape.INVISIBLE : RenderShape.MODEL;
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    builder.add(FACING, rollProperty(), FORMED, HIDDEN, APPEARANCE);
  }

  @Override
  protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
    return ControllerBlockGeometry.shape(level, pos);
  }

  @Override
  protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
    return ControllerBlockGeometry.shape(level, pos);
  }

  protected static MachineOrientation orientationOf(BlockState state, IntegerProperty roll) {
    return MachineOrientation.of(state.getValue(FACING), state.getValue(roll));
  }

  protected static BlockState withOrientation(BlockState state, MachineOrientation orientation, IntegerProperty roll) {
    return state.setValue(FACING, orientation.front()).setValue(roll, orientation.roll());
  }

  @Override
  protected BlockState rotate(BlockState state, Rotation rotation) {
    var orientation = orientationOf(state, rollProperty());

    return withOrientation(
        state,
        MachineOrientation.fromFrontAndUp(rotation.rotate(orientation.front()), rotation.rotate(orientation.up())),
        rollProperty());
  }

  @Override
  protected BlockState mirror(BlockState state, Mirror mirror) {
    var orientation = orientationOf(state, rollProperty());

    return withOrientation(
        state,
        MachineOrientation.fromFrontAndUp(mirror.mirror(orientation.front()), mirror.mirror(orientation.up())),
        rollProperty());
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
}
