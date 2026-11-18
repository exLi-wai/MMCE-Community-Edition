package com.lw.modularmachine.machine.recipe.resource.world;

import com.lw.modularmachine.api.recipe.resource.RecipeResourceParticipant;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.ApiStatus;

/**
 * Reversible numeric write into an already initialized upstream chunk
 * attachment.
 */
@ApiStatus.Internal
public final class ChunkPool implements WorldPoolExecution.Pool {
  private static final double UNITS = 1_000;
  private final ServerLevel level;
  private final LevelChunk chunk;
  private final BooleanSupplier identity;
  private final DoubleSupplier stock;
  private final DoubleSupplier ceiling;
  private final DoubleConsumer setter;
  private final Runnable sync;
  private final double original;
  private final double capacity;

  public ChunkPool(ServerLevel level, LevelChunk chunk, BooleanSupplier identity, DoubleSupplier stock, DoubleSupplier ceiling, DoubleConsumer setter, Runnable sync) {
    this.level = level;
    this.chunk = chunk;
    this.identity = identity;
    this.stock = stock;
    this.ceiling = ceiling;
    this.setter = setter;
    this.sync = sync;
    original = stock.getAsDouble();
    capacity = ceiling.getAsDouble();
  }

  @Override
  public long amount() {
    return Math.round(original * UNITS);
  }

  @Override
  public long capacity() {
    return (long) Math.floor(capacity * UNITS);
  }

  @Override
  public boolean current() {
    var position = chunk.getPos();
    return level.getChunkSource().getChunkNow(position.x, position.z) == chunk
        && identity.getAsBoolean()
        && stock.getAsDouble() == original
        && ceiling.getAsDouble() == capacity;
  }

  @Override
  public RecipeResourceParticipant acquire(long before, long after) {
    if (!current() || before != amount() || after < 0 || after > capacity()) {
      throw new IllegalStateException("Chunk stock changed before recipe acquisition");
    }
    double requested = (after - before) / UNITS;
    setter.accept(Math.clamp(original + requested, 0, capacity));
    if (Math.round(stock.getAsDouble() * UNITS) != after) {
      setter.accept(original);
      throw new IllegalStateException("Chunk resource could not represent the planned amount");
    }
    return commit -> {
      if (!commit) {
        setter.accept(original);
      }
    };
  }

  @Override
  public void publish() {
    chunk.setUnsaved(true);
    sync.run();
  }
}
