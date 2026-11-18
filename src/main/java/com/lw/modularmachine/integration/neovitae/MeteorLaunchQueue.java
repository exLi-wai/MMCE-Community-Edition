package com.lw.modularmachine.integration.neovitae;

import java.util.LinkedHashMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class MeteorLaunchQueue extends SavedData {
  private static final String NAME = "mmcece_detached_meteor_launches";
  private static final Factory<MeteorLaunchQueue> FACTORY = new Factory<>(
      MeteorLaunchQueue::new,
      MeteorLaunchQueue::load);
  private final LinkedHashMap<UUID, Launch> launches = new LinkedHashMap<>();

  private MeteorLaunchQueue() {
  }

  static void enqueue(ServerLevel level, BlockPos origin, ResourceLocation catalyst, UUID id) {
    var queue = level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    if (queue.launches.putIfAbsent(id, new Launch(origin.immutable(), catalyst)) == null) {
      queue.setDirty();
    }
  }

  public static void tick(LevelTickEvent.Post event) {
    if (event.getLevel() instanceof ServerLevel level && level.getGameTime() % 10 == 0) {
      var queue = level.getDataStorage().get(FACTORY, NAME);
      if (queue != null) {
        queue.advance(level);
      }
    }
  }

  private void advance(ServerLevel level) {
    int budget = Math.min(16, launches.size());
    for (int visited = 0; visited < budget; visited++) {
      var entry = launches.firstEntry();
      var launch = entry.getValue();
      var id = entry.getKey();
      if (level.isPositionEntityTicking(launch.origin())
          && level.areEntitiesLoaded(ChunkPos.asLong(launch.origin()))
          && (level.getEntity(id) != null
              || MeteorHatchBlockEntity.trySpawn(level, launch.origin(), launch.catalyst(), id))) {
        launches.remove(id);
        setDirty();
      } else {
        launches.putLast(id, launch);
      }
    }
  }

  @Override
  public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
    var entries = new ListTag();
    launches.forEach((id, launch) -> {
      var entry = new CompoundTag();
      entry.putUUID("id", id);
      entry.putLong("origin", launch.origin().asLong());
      entry.putString("catalyst", launch.catalyst().toString());
      entries.add(entry);
    });
    tag.put("launches", entries);
    return tag;
  }

  private static MeteorLaunchQueue load(CompoundTag tag, HolderLookup.Provider registries) {
    var queue = new MeteorLaunchQueue();
    var entries = tag.getList("launches", Tag.TAG_COMPOUND);
    for (int index = 0; index < entries.size(); index++) {
      var entry = entries.getCompound(index);
      var catalyst = ResourceLocation.tryParse(entry.getString("catalyst"));
      if (catalyst != null && entry.hasUUID("id") && entry.contains("origin", Tag.TAG_LONG)) {
        queue.launches.put(entry.getUUID("id"), new Launch(BlockPos.of(entry.getLong("origin")), catalyst));
      }
    }
    return queue;
  }

  private record Launch(BlockPos origin, ResourceLocation catalyst) {
  }
}
