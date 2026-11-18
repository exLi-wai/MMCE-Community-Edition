package com.lw.modularmachine.integration.naturesaura;

import com.lw.modularmachine.block.TickingRecipeHatchBlock;
import com.lw.modularmachine.blockentity.BufferedRecipeHatchBlockEntity;
import com.lw.modularmachine.machine.recipe.RecipeLifecyclePublisher;
import com.lw.modularmachine.registry.MachineRegistries;
import de.ellpeck.naturesaura.api.aura.chunk.IAuraChunk;
import de.ellpeck.naturesaura.api.aura.type.IAuraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

public final class AuraInputHatchBlockEntity extends BufferedRecipeHatchBlockEntity implements TickingRecipeHatchBlock.ServerTicking {
  private static final int RADIUS = 20;
  private static final int CAPACITY = 500_000;

  public AuraInputHatchBlockEntity(BlockPos position, BlockState state) {
    super(MachineRegistries.AURA_INPUT_ENTITY.get(), position, state, CAPACITY, "natural_aura");
  }

  @Override
  public void tickHatch() {
    if (!live()
        || RecipeLifecyclePublisher.isDispatching()
        || !(level instanceof ServerLevel server)
        || Math.floorMod(server.getGameTime() + worldPosition.asLong(), 10) != 0) {
      return;
    }
    int requested = (int) Math.min(space(), 6_400);
    var type = IAuraType.forLevel(server);
    if (requested == 0 || type == null || !loadedNeighborhood(server)) {
      return;
    }
    requested = Math.min(requested, IAuraChunk.getAuraInArea(server, worldPosition, RADIUS));
    if (requested <= 0) {
      return;
    }
    var position = IAuraChunk.getHighestSpot(server, worldPosition, RADIUS, worldPosition);
    if (position != null && server.hasChunkAt(position)) {
      var chunk = IAuraChunk.getAuraChunk(server, position);
      if (chunk != null && type.isSimilar(chunk.getType())) {
        int accepted = chunk.drainAura(position, requested, false, false);
        receive(Math.clamp(accepted, 0, requested), false);
      }
    }
  }

  private boolean loadedNeighborhood(ServerLevel server) {
    for (int chunkX = (worldPosition.getX() - RADIUS) >> 4; chunkX <= (worldPosition.getX() + RADIUS) >> 4; chunkX++) {
      for (int chunkZ = (worldPosition.getZ() - RADIUS) >> 4; chunkZ <= (worldPosition
          .getZ() + RADIUS) >> 4; chunkZ++) {
        if (server.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
          return false;
        }
      }
    }
    return true;
  }
}
