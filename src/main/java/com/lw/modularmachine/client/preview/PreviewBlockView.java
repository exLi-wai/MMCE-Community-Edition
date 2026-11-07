package com.lw.modularmachine.client.preview;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.ApiStatus;

/** Render-thread schematic neighbors; never passed to packing workers. */
@ApiStatus.Internal
public final class PreviewBlockView implements BlockAndTintGetter {
  private static final ThreadLocal<Integer> ACTIVE_MACHINE_COLOR = new ThreadLocal<>();
  private final PreviewRenderScene scene;
  private final int layer, step;

  public PreviewBlockView(PreviewRenderScene scene, int layer, int step) {
    this.scene = scene;
    this.layer = layer;
    this.step = step;
  }

  public int machineColor() {
    return scene.machineColor();
  }

  public static int activeMachineColor() {
    return ACTIVE_MACHINE_COLOR.get() == null ? -1 : ACTIVE_MACHINE_COLOR.get();
  }

  public static Scope pushMachineColor(int color) {
    Integer previous = ACTIVE_MACHINE_COLOR.get();
    ACTIVE_MACHINE_COLOR.set(color & 0xFFFFFF);
    return () -> {
      if (previous == null) {
        ACTIVE_MACHINE_COLOR.remove();
      } else {
        ACTIVE_MACHINE_COLOR.set(previous);
      }
    };
  }

  @FunctionalInterface
  public interface Scope extends AutoCloseable {
    @Override
    void close();
  }

  @Override
  public BlockState getBlockState(BlockPos position) {
    var entry = scene.at(position);
    if (entry == null || entry.states().isEmpty() || layer != Integer.MIN_VALUE && position.getY() != layer) {
      return Blocks.AIR.defaultBlockState();
    }
    return entry.states().get(Math.floorMod(step, entry.states().size()));
  }

  @Override
  public FluidState getFluidState(BlockPos pos) {
    return getBlockState(pos).getFluidState();
  }
  @Override
  public BlockEntity getBlockEntity(BlockPos pos) {
    return null;
  }
  @Override
  public int getHeight() {
    return Minecraft.getInstance().level.getHeight();
  }
  @Override
  public int getMinBuildHeight() {
    return Minecraft.getInstance().level.getMinBuildHeight();
  }
  @Override
  public float getShade(Direction direction, boolean shade) {
    return Minecraft.getInstance().level.getShade(direction, shade);
  }
  @Override
  public LevelLightEngine getLightEngine() {
    return Minecraft.getInstance().level.getLightEngine();
  }
  @Override
  public int getBrightness(LightLayer type, BlockPos pos) {
    return 15;
  }
  @Override
  public int getRawBrightness(BlockPos pos, int amount) {
    return 15;
  }
  @Override
  public int getBlockTint(BlockPos pos, ColorResolver resolver) {
    return Minecraft.getInstance().level.getBlockTint(pos, resolver);
  }
}
