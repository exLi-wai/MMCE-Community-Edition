package com.lw.modularmachine.integration.neovitae;

import com.breakinblocks.neovitae.common.dataattachment.NVDataAttachments;
import com.breakinblocks.neovitae.common.datacomponent.SpiritusType;
import com.breakinblocks.neovitae.spiritus.WorldSpiritusHandler;
import com.lw.modularmachine.machine.recipe.resource.world.ChunkPool;
import com.lw.modularmachine.machine.recipe.resource.world.WorldPoolExecution;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class SpiritusPoolAccess implements WorldPoolExecution.Access {
  @Override
  public WorldPoolExecution.Pool open(ServerLevel level, LevelChunk chunk, BlockPos hatch, String kind) {
    var type = SpiritusType.valueOf(kind.toUpperCase(Locale.ROOT));
    var attachment = NVDataAttachments.SPIRITUS_CHUNK.get();
    var data = chunk.getExistingData(attachment).orElse(null);
    if (data == null) {
      return null;
    }
    return new ChunkPool(
        level,
        chunk,
        () -> chunk.getExistingData(attachment).orElse(null) == data,
        () -> data.getSpiritus(type),
        () -> data.getMaxSpiritus(type),
        value -> data.setSpiritus(type, value),
        () -> WorldSpiritusHandler.syncChunkToTrackingPlayers(level, chunk.getPos(), data));
  }
}
