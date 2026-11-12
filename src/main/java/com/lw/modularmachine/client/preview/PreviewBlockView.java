package com.lw.modularmachine.client.preview;

import net.neoforged.neoforge.client.model.data.ModelData;
import com.lw.modularmachine.ModularMachine;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
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
  private int layer, step;
  private final boolean worldNeighbors;
  private PreviewLevel level;
  private final LinkedHashMap<Long, BlockEntity> entities = new LinkedHashMap<>(16, .75F, true);
  private final Set<BlockState> failedEntities = new HashSet<>();

  public PreviewBlockView(PreviewRenderScene scene, int layer, int step) {
    this(scene, layer, step, false);
  }

  PreviewBlockView(PreviewRenderScene scene, int layer, int step, boolean worldNeighbors) {
    this.scene = scene;
    this.layer = layer;
    this.step = step;
    this.worldNeighbors = worldNeighbors;
  }

  void setState(int layer, int step) {
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
    if (entry == null && worldNeighbors) {
      var level = Minecraft.getInstance().level;
      return level != null && level.hasChunkAt(position) ? level.getBlockState(position) : Blocks.AIR
          .defaultBlockState();
    }
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
    var state = getBlockState(pos);
    if (!state.hasBlockEntity()
        || !(state.getBlock() instanceof EntityBlock factory)
        || failedEntities.contains(state)) {
      return null;
    }
    var cached = entities.get(pos.asLong());
    if (cached != null && cached.getBlockState() == state) {
      return cached;
    }
    try {
      // Detached visual instances: never inserted into a level, ticked, or fed
      // live inventory/NBT. Models query schematic neighbors through this view.
      var entity = factory.newBlockEntity(pos, state);
      if (entity != null) {
        if (level == null) {
          level = new PreviewLevel(this, Minecraft.getInstance().level);
        }
        entity.setLevel(level);
        entities.put(pos.asLong(), entity);
        while (entities.size() > 2048) {
          entities.remove(entities.keySet().iterator().next());
        }
      }
      return entity;
    } catch (RuntimeException failure) {
      failedEntities.add(state);
      ModularMachine.LOGGER.warn("Cannot create preview block entity for {}", state, failure);
      return null;
    }
  }

  ModelData modelData(BlockPos pos, BlockState state) {
    var world = Minecraft.getInstance().level;
    if (worldNeighbors
        && world != null
        && world.hasChunkAt(pos)
        && world.getBlockState(pos).getBlock() == state.getBlock()) {
      var entity = world.getBlockEntity(pos);
      if (entity != null) {
        return entity.getModelData();
      }
    }
    var entity = getBlockEntity(pos);
    return entity == null ? ModelData.EMPTY : entity.getModelData();
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
