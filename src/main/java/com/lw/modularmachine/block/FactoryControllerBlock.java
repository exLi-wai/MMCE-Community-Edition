package com.lw.modularmachine.block;

import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.machine.controller.DedicatedControllerCatalog.Entry;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.orientation.ControllerOrientationActions;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.registry.MachineRegistries;
import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.storage.loot.LootParams.Builder;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;

public final class FactoryControllerBlock extends ControllerBlock {
  public static final MapCodec<FactoryControllerBlock> CODEC = simpleCodec(FactoryControllerBlock::new);
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
  public FactoryControllerBlock(Properties properties) {
    this(properties, null);
  }
  public FactoryControllerBlock(Properties properties, Entry dedicated) {
    super(properties, dedicated);
  }

  @Override
  protected MapCodec<? extends BaseEntityBlock> codec() {
    return CODEC;
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

  @Override
  public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
    return new FactoryControllerBlockEntity(pos, state);
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
