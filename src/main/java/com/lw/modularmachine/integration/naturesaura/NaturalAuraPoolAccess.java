package com.lw.modularmachine.integration.naturesaura;

import com.lw.modularmachine.api.recipe.resource.RecipeResourceParticipant;
import com.lw.modularmachine.machine.recipe.resource.world.WorldPoolExecution;
import de.ellpeck.naturesaura.api.NaturesAuraAPI;
import de.ellpeck.naturesaura.api.aura.chunk.IAuraChunk;
import de.ellpeck.naturesaura.api.aura.type.IAuraType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class NaturalAuraPoolAccess implements WorldPoolExecution.Access {
  public static boolean knownType(ResourceLocation id) {
    return NaturesAuraAPI.AURA_TYPES.containsKey(id);
  }

  @Override
  public WorldPoolExecution.Pool open(ServerLevel level, LevelChunk chunk, BlockPos hatch, String kind) {
    if (!areaLoaded(level, hatch)) {
      return null;
    }
    var attachment = NaturesAuraAPI.AURA_CHUNK_ATTACHMENT;
    var existing = chunk.getExistingData(attachment).orElse(null);
    var type = existing == null ? IAuraType.forLevel(level) : existing.getType();
    if (type == null || !type.getName().toString().equals(kind)) {
      return null;
    }
    int spot = existing == null ? 0 : existing.getDrainSpot(hatch);
    int balance = IAuraChunk.getAuraInArea(level, hatch, 1);
    long capacity = Math.min(Integer.MAX_VALUE, (long) balance + Integer.MAX_VALUE - Math.max(spot, 0));
    return new WorldPoolExecution.Pool() {
      @Override
      public BlockPos localOutput() {
        return hatch;
      }

      @Override
      public long amount() {
        return balance;
      }

      @Override
      public long capacity() {
        return capacity;
      }

      @Override
      public boolean current() {
        return spotCurrent() && IAuraChunk.getAuraInArea(level, hatch, 1) == balance;
      }

      private boolean spotCurrent() {
        var position = chunk.getPos();
        return areaLoaded(level, hatch)
            && level.getChunkSource().getChunkNow(position.x, position.z) == chunk
            && chunk.getExistingData(attachment).orElse(null) == existing
            && (existing == null || existing.getDrainSpot(hatch) == spot);
      }

      @Override
      public RecipeResourceParticipant acquire(long before, long after) {
        if (!spotCurrent() || before != amount() || after <= before || after > capacity()) {
          throw new IllegalStateException("Natural aura output changed before acquisition");
        }
        int increase = Math.toIntExact(after - before);
        var aura = chunk.getData(attachment);
        var saved = existing == null ? null : aura.serializeNBT(level.registryAccess());
        try {
          if (aura.storeAura(hatch, increase, false, false) != increase
              || aura.getDrainSpot(hatch) != (long) spot + increase) {
            throw new IllegalStateException("Natural aura output did not accept its reserved amount");
          }
        } catch (RuntimeException failure) {
          restore(aura, saved);
          throw failure;
        }
        return commit -> {
          if (!commit) {
            restore(aura, saved);
          }
        };
      }

      private void restore(IAuraChunk aura, net.minecraft.nbt.CompoundTag saved) {
        if (saved == null) {
          aura.deserializeNBT(level.registryAccess(), new net.minecraft.nbt.CompoundTag());
          chunk.removeData(attachment);
        } else {
          aura.deserializeNBT(level.registryAccess(), saved);
        }
        aura.markDirty();
      }

      @Override
      public void publish() {
      }
    };
  }

  private static boolean areaLoaded(ServerLevel level, BlockPos position) {
    var start = new ChunkPos(position.offset(-1, 0, -1));
    var end = new ChunkPos(position.offset(1, 0, 1));
    for (int chunkX = start.x; chunkX <= end.x; chunkX++) {
      for (int chunkZ = start.z; chunkZ <= end.z; chunkZ++) {
        if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
          return false;
        }
      }
    }
    return true;
  }
}
