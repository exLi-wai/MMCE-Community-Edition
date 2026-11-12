package com.lw.modularmachine.client.preview;

import net.minecraft.world.level.ColorResolver;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LightLayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.BlockGetter;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.Difficulty;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal visual level. No chunks, entities, ticks, world writes or effects
 * are forwarded to the client world; block queries resolve through the
 * schematic.
 */
@ApiStatus.Internal
final class PreviewLevel extends Level {
  private final PreviewBlockView view;
  private final ClientLevel environment;
  private final Scoreboard scoreboard = new Scoreboard();
  private final TickRateManager tickRate = new TickRateManager();
  private final ChunkSource chunks = new ChunkSource() {
    private final LevelLightEngine light = new LevelLightEngine(this, false, false);
    @Override
    public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) {
      return null;
    }
    @Override
    public void tick(BooleanSupplier time, boolean tickChunks) {
    }
    @Override
    public String gatherStats() {
      return "Preview";
    }
    @Override
    public int getLoadedChunksCount() {
      return 0;
    }
    @Override
    public LevelLightEngine getLightEngine() {
      return light;
    }
    @Override
    public BlockGetter getLevel() {
      return PreviewLevel.this;
    }
  };
  private final LevelEntityGetter<Entity> entities = new LevelEntityGetter<>() {
    @Override
    public Entity get(int id) {
      return null;
    }
    @Override
    public Entity get(UUID id) {
      return null;
    }
    @Override
    public Iterable<Entity> getAll() {
      return List.of();
    }
    @Override
    public <U extends Entity> void get(EntityTypeTest<Entity, U> test, AbortableIterationConsumer<U> consumer) {
    }
    @Override
    public void get(AABB bounds, Consumer<Entity> consumer) {
    }
    @Override
    public <U extends Entity> void get(EntityTypeTest<Entity, U> test, AABB bounds, AbortableIterationConsumer<U> consumer) {
    }
  };

  PreviewLevel(PreviewBlockView view, ClientLevel environment) {
    super(new ClientLevel.ClientLevelData(Difficulty.NORMAL, false, false), environment.dimension(),
        environment.registryAccess(), environment.dimensionTypeRegistration(), () -> InactiveProfiler.INSTANCE, true,
        false, 0L, 0);
    this.view = view;
    this.environment = environment;
  }

  @Override
  public BlockState getBlockState(BlockPos pos) {
    return view.getBlockState(pos);
  }
  @Override
  public FluidState getFluidState(BlockPos pos) {
    return view.getFluidState(pos);
  }
  @Override
  public BlockEntity getBlockEntity(BlockPos pos) {
    return view.getBlockEntity(pos);
  }
  @Override
  public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursion) {
    return false;
  }
  @Override
  public void setBlockEntity(BlockEntity entity) {
  }
  @Override
  public void removeBlockEntity(BlockPos pos) {
  }
  @Override
  public void blockEntityChanged(BlockPos pos) {
  }
  @Override
  public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
  }
  @Override
  public void updateNeighborsAt(BlockPos pos, Block block) {
  }
  @Override
  public void updateNeighbourForOutputSignal(BlockPos pos, Block block) {
  }
  @Override
  public boolean addFreshEntity(Entity entity) {
    return false;
  }
  @Override
  public ChunkSource getChunkSource() {
    return chunks;
  }
  @Override
  public String gatherChunkSourceStats() {
    return "Preview";
  }
  @Override
  public LevelTickAccess<Block> getBlockTicks() {
    return BlackholeTickAccess.emptyLevelList();
  }
  @Override
  public LevelTickAccess<Fluid> getFluidTicks() {
    return BlackholeTickAccess.emptyLevelList();
  }
  @Override
  public List<? extends Player> players() {
    return List.of();
  }
  @Override
  public Entity getEntity(int id) {
    return null;
  }
  @Override
  protected LevelEntityGetter<Entity> getEntities() {
    return entities;
  }
  @Override
  public TickRateManager tickRateManager() {
    return tickRate;
  }
  @Override
  public Scoreboard getScoreboard() {
    return scoreboard;
  }
  @Override
  public RecipeManager getRecipeManager() {
    return environment.getRecipeManager();
  }
  @Override
  public PotionBrewing potionBrewing() {
    return environment.potionBrewing();
  }
  @Override
  public FeatureFlagSet enabledFeatures() {
    return environment.enabledFeatures();
  }
  @Override
  public long getGameTime() {
    return environment.getGameTime();
  }
  @Override
  public long getDayTime() {
    return environment.getDayTime();
  }
  @Override
  public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
    return registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS);
  }
  @Override
  public int getBrightness(LightLayer layer, BlockPos pos) {
    return 15;
  }
  @Override
  public int getRawBrightness(BlockPos pos, int amount) {
    return 15;
  }
  @Override
  public float getShade(Direction direction, boolean shade) {
    return view.getShade(direction, shade);
  }
  @Override
  public int getBlockTint(BlockPos pos, ColorResolver resolver) {
    return view.getBlockTint(pos, resolver);
  }
  @Override
  public void playSeededSound(Player player, double x, double y, double z, Holder<SoundEvent> sound, SoundSource category, float volume, float pitch, long seed) {
  }
  @Override
  public void playSeededSound(Player player, Entity entity, Holder<SoundEvent> sound, SoundSource category, float volume, float pitch, long seed) {
  }
  @Override
  public void playSound(Player player, BlockPos pos, SoundEvent sound, SoundSource category, float volume, float pitch) {
  }
  @Override
  public void addParticle(ParticleOptions particle, double x, double y, double z, double dx, double dy, double dz) {
  }
  @Override
  public void levelEvent(Player player, int type, BlockPos pos, int data) {
  }
  @Override
  public void gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context) {
  }
  @Override
  public void destroyBlockProgress(int id, BlockPos pos, int progress) {
  }
  @Override
  public MapItemSavedData getMapData(MapId id) {
    return null;
  }
  @Override
  public void setMapData(MapId id, MapItemSavedData data) {
  }
  @Override
  public MapId getFreeMapId() {
    return new MapId(0);
  }
  @Override
  public void setDayTimeFraction(float fraction) {
  }
  @Override
  public float getDayTimeFraction() {
    return environment.getDayTimeFraction();
  }
  @Override
  public float getDayTimePerTick() {
    return environment.getDayTimePerTick();
  }
  @Override
  public void setDayTimePerTick(float value) {
  }
}
