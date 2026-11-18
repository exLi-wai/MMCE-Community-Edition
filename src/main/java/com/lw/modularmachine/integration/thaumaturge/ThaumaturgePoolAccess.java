package com.lw.modularmachine.integration.thaumaturge;

import com.leclowndu93150.thaumaturge.content.aura.AuraManager;
import com.leclowndu93150.thaumaturge.registry.TTAttachments;
import com.lw.modularmachine.machine.recipe.resource.world.ChunkPool;
import com.lw.modularmachine.machine.recipe.resource.world.WorldPoolExecution;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class ThaumaturgePoolAccess implements WorldPoolExecution.Access {
  private final boolean flux;

  public ThaumaturgePoolAccess(boolean flux) {
    this.flux = flux;
  }

  @Override
  public WorldPoolExecution.Pool open(ServerLevel level, LevelChunk chunk, BlockPos hatch, String kind) {
    var attachment = TTAttachments.AURA.get();
    var data = chunk.getExistingData(attachment).orElse(null);
    if (data == null || !data.isInitialized()) {
      return null;
    }
    return new ChunkPool(
        level,
        chunk,
        () -> chunk.getExistingData(attachment).orElse(null) == data && data.isInitialized(),
        flux ? data::getFlux : data::getVis,
        () -> AuraManager.AURA_CEILING,
        value -> {
          if (flux) {
            data.setFlux((float) value);
          } else {
            data.setVis((float) value);
          }
        },
        () -> {
        });
  }
}
