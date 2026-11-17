package com.lw.modularmachine.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import java.util.Locale;

/**
 * Script-registered structure component with the original idle/working
 * variants.
 */
public class StatedMachineComponentBlock extends Block {
  public enum WorkingState implements StringRepresentable {
    IDLE,
    WORKING;

    @Override
    public String getSerializedName() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public static final EnumProperty<WorkingState> WORKING_STATE = EnumProperty
      .create("working_state", WorkingState.class);
  public static final MapCodec<StatedMachineComponentBlock> CODEC = simpleCodec(StatedMachineComponentBlock::new);

  public StatedMachineComponentBlock(Properties properties) {
    super(properties);
    registerDefaultState(stateDefinition.any().setValue(WORKING_STATE, WorkingState.IDLE));
  }

  @Override
  protected MapCodec<? extends Block> codec() {
    return CODEC;
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    builder.add(WORKING_STATE);
  }
}
