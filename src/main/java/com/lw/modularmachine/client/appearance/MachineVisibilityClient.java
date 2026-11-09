package com.lw.modularmachine.client.appearance;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.network.MachineVisibilityPayloads;
import com.lw.modularmachine.registry.MachineRegistries;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal client display membership; world block states and component data
 * remain untouched.
 */
@ApiStatus.Internal
@EventBusSubscriber(modid = ModularMachine.MODID, value = Dist.CLIENT)
public final class MachineVisibilityClient {
  private static final ModelProperty<List<Member>> MEMBERS = new ModelProperty<>();
  private static final ConcurrentHashMap<Long, List<Member>> MEMBERS_BY_POSITION = new ConcurrentHashMap<>();
  private static final Map<Long, Owner> OWNERS = new HashMap<>();
  private static final Map<Long, Chunk> CHUNKS = new HashMap<>();
  private static final ArrayDeque<Owner> OWNER_WORK = new ArrayDeque<>();
  private static final ArrayDeque<Chunk> CHUNK_WORK = new ArrayDeque<>();
  private static final LinkedHashSet<Long> DIRTY_SECTIONS = new LinkedHashSet<>();
  private static volatile Set<ResourceLocation> componentModels = Set.of();
  private static ClientLevel activeLevel;

  private MachineVisibilityClient() {
  }

  private record Member(Owner owner, Chunk chunk) {
    boolean active() {
      return owner.active && !owner.removed && chunk.active;
    }
  }

  private static final class Owner {
    final long generation;
    ArrayList<Long> positions = new ArrayList<>();
    Set<Long> positionSet = new HashSet<>();
    ArrayList<Long> sections = new ArrayList<>();
    Set<Long> sectionSet = new HashSet<>();
    long revision = -1;
    volatile boolean active, removed;
    boolean queued;
    int dirtyCursor, cleanupCursor;

    Owner(long generation) {
      this.generation = generation;
    }
  }

  private static final class Chunk {
    final ArrayList<Long> positions = new ArrayList<>();
    final Set<Long> positionSet = new HashSet<>();
    final Map<Long, Set<Owner>> ownersAtPosition = new HashMap<>();
    volatile boolean active = true;
    int cleanupCursor;
  }

  public static void receive(MachineVisibilityPayloads.Update update) {
    var level = Minecraft.getInstance().level;
    if (level == null || !level.dimension().location().equals(update.dimension())) {
      return;
    }
    useLevel(level);
    if (update.membership() && !level.hasChunk(update.chunk().x, update.chunk().z)) {
      return;
    }
    long key = update.owner().asLong();
    Owner owner = OWNERS.get(key);
    if (owner != null && update.generation() < owner.generation) {
      return;
    }
    if (owner == null || update.generation() > owner.generation) {
      if (owner != null) {
        owner.active = false;
        owner.removed = true;
        schedule(owner);
      }
      owner = new Owner(update.generation());
      OWNERS.put(key, owner);
    }
    if (owner.removed) {
      return;
    }
    if (update.revision() > owner.revision) {
      boolean changed = owner.active != update.active() || update.removed();
      owner.revision = update.revision();
      owner.active = update.active();
      owner.removed = update.removed();
      if (changed) {
        schedule(owner);
      }
    }
    if (!update.membership() || owner.removed) {
      return;
    }
    Chunk chunk = CHUNKS.computeIfAbsent(update.chunk().toLong(), ignored -> new Chunk());
    for (var position : update.positions()) {
      long packed = position.asLong();
      Set<Owner> references = chunk.ownersAtPosition.computeIfAbsent(packed, ignored -> {
        if (chunk.positionSet.add(packed)) {
          chunk.positions.add(packed);
        }
        return new HashSet<>();
      });
      if (!references.add(owner)) {
        continue;
      }
      var member = new Member(owner, chunk);
      MEMBERS_BY_POSITION.compute(packed, (ignored, previous) -> {
        var next = previous == null ? new ArrayList<Member>() : new ArrayList<>(previous);
        next.add(member);
        return List.copyOf(next);
      });
      if (owner.positionSet.add(packed)) {
        owner.positions.add(packed);
      }
      long section = SectionPos.asLong(position);
      if (owner.sectionSet.add(section)) {
        owner.sections.add(section);
      }
      DIRTY_SECTIONS.add(section);
    }
  }

  private static void useLevel(ClientLevel level) {
    if (activeLevel != level) {
      clear();
      activeLevel = level;
    }
  }

  private static void schedule(Owner owner) {
    owner.dirtyCursor = 0;
    if (!owner.queued) {
      owner.queued = true;
      OWNER_WORK.add(owner);
    }
  }

  public static void invalidateMeshes() {
    Minecraft.getInstance().execute(() -> {
      if (activeLevel == Minecraft.getInstance().level) {
        OWNERS.values().forEach(MachineVisibilityClient::schedule);
      }
    });
  }

  @SubscribeEvent
  static void onTick(ClientTickEvent.Post event) {
    var mc = Minecraft.getInstance();
    if (mc.level == null) {
      clear();
      return;
    }
    useLevel(mc.level);
    long deadline = System.nanoTime() + 250_000L;
    int budget = 256;
    while (budget-- > 0 && System.nanoTime() < deadline && !CHUNK_WORK.isEmpty()) {
      Chunk chunk = CHUNK_WORK.remove();
      if (chunk.cleanupCursor < chunk.positions.size()) {
        long position = chunk.positions.get(chunk.cleanupCursor++);
        MEMBERS_BY_POSITION.computeIfPresent(position, (ignored, previous) -> remaining(previous, null, chunk));
      }
      if (chunk.cleanupCursor < chunk.positions.size()) {
        CHUNK_WORK.add(chunk);
      }
    }
    budget = 256;
    deadline = System.nanoTime() + 500_000L;
    while (budget-- > 0 && System.nanoTime() < deadline && !OWNER_WORK.isEmpty()) {
      Owner owner = OWNER_WORK.remove();
      if (owner.dirtyCursor < owner.sections.size()) {
        DIRTY_SECTIONS.add(owner.sections.get(owner.dirtyCursor++));
      }
      if (owner.removed && owner.cleanupCursor < owner.positions.size()) {
        long position = owner.positions.get(owner.cleanupCursor++);
        MEMBERS_BY_POSITION.computeIfPresent(position, (ignored, previous) -> remaining(previous, owner, null));
        var blockPosition = BlockPos.of(position);
        Chunk chunk = CHUNKS
            .get(net.minecraft.world.level.ChunkPos.asLong(blockPosition.getX() >> 4, blockPosition.getZ() >> 4));
        if (chunk != null) {
          Set<Owner> references = chunk.ownersAtPosition.get(position);
          if (references != null) {
            references.remove(owner);
            if (references.isEmpty()) {
              chunk.ownersAtPosition.remove(position);
            }
          }
        }
      }
      if (owner.dirtyCursor < owner.sections.size() || owner.removed && owner.cleanupCursor < owner.positions.size()) {
        OWNER_WORK.add(owner);
      } else {
        owner.queued = false;
        if (owner.removed) {
          owner.positions = new ArrayList<>();
          owner.positionSet = new HashSet<>();
          owner.sections = new ArrayList<>();
          owner.sectionSet = new HashSet<>();
        }
      }
    }
    int sections = 128;
    deadline = System.nanoTime() + 250_000L;
    var iterator = DIRTY_SECTIONS.iterator();
    while (sections-- > 0 && System.nanoTime() < deadline && iterator.hasNext()) {
      long section = iterator.next();
      iterator.remove();
      int x = SectionPos.x(section), y = SectionPos.y(section), z = SectionPos.z(section);
      if (mc.level.hasChunk(x, z)) {
        mc.levelRenderer.setSectionDirty(x, y, z);
      }
    }
  }

  private static List<Member> remaining(List<Member> previous, Owner owner, Chunk chunk) {
    var result = previous.stream().filter(member -> member.owner() != owner && member.chunk() != chunk).toList();

    return result.isEmpty() ? null : result;
  }

  @SubscribeEvent
  static void onChunkUnload(ChunkEvent.Unload event) {
    if (event.getLevel() != activeLevel) {
      return;
    }
    Chunk chunk = CHUNKS.remove(event.getChunk().getPos().toLong());
    if (chunk != null) {
      chunk.active = false;
      CHUNK_WORK.add(chunk);
    }
  }

  @SubscribeEvent
  static void onLevelUnload(LevelEvent.Unload event) {
    if (event.getLevel() == activeLevel) {
      clear();
    }
  }

  @SubscribeEvent
  static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
    clear();
  }

  private static void clear() {
    OWNERS.values().forEach(owner -> owner.active = false);
    CHUNKS.values().forEach(chunk -> chunk.active = false);
    MEMBERS_BY_POSITION.clear();
    OWNERS.clear();
    CHUNKS.clear();
    OWNER_WORK.clear();
    CHUNK_WORK.clear();
    DIRTY_SECTIONS.clear();
    activeLevel = null;
  }

  public static void initializeModels() {
    var ids = new HashSet<ResourceLocation>();
    MachineRegistries.CASINGS.values().forEach(holder -> ids.add(holder.getId()));
    MachineRegistries.PORTS.values().forEach(holder -> ids.add(holder.getId()));
    MachineRegistries.PARALLEL_CONTROLLERS.values().forEach(holder -> ids.add(holder.getId()));
    MachineRegistries.UPGRADE_BUSES.values().forEach(holder -> ids.add(holder.getId()));
    ids.add(MachineRegistries.SMART_INTERFACE.getId());
    componentModels = Set.copyOf(ids);
  }

  public static void wrapModels(ModelEvent.ModifyBakingResult event) {
    Set<ResourceLocation> models = componentModels;
    event.getModels().replaceAll((location, model) -> {
      if (location.getVariant().equals("inventory")
          || location.getVariant().equals("standalone")
          || !models.contains(location.id())
          || model instanceof HiddenModel) {
        return model;
      }

      return new HiddenModel(model);
    });
  }

  private static boolean hidden(ModelData data) {
    List<Member> members = data.get(MEMBERS);
    if (members != null) {
      for (var member : members) {
        if (member.active()) {
          return true;
        }
      }
    }

    return false;
  }

  private static final class HiddenModel extends BakedModelWrapper<BakedModel> {
    HiddenModel(BakedModel original) {
      super(original);
    }

    @Override
    public ModelData getModelData(BlockAndTintGetter level, BlockPos position, BlockState state, ModelData data) {
      ModelData resolved = originalModel.getModelData(level, position, state, data);
      if (!(level instanceof ClientLevel) && !(level instanceof RenderChunkRegion)) {
        return resolved;
      }
      var members = MEMBERS_BY_POSITION.get(position.asLong());

      return members == null ? resolved : resolved.derive().with(MEMBERS, members).build();
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random, ModelData data, RenderType type) {
      return hidden(data) ? List.of() : originalModel.getQuads(state, side, random, data, type);
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource random, ModelData data) {
      return hidden(data) ? ChunkRenderTypeSet.none() : originalModel.getRenderTypes(state, random, data);
    }
  }
}
