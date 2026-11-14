package com.lw.modularmachine.api.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Objects;

/**
 * Start-only factory thread selection; paid runs retain their admitted owner.
 */
public record RecipeExecutionOptions(String threadName, int maxThreads) {
  public static final int MAX_THREADS = 256;
  public static final RecipeExecutionOptions NONE = new RecipeExecutionOptions("", 0);
  public static final MapCodec<RecipeExecutionOptions> MAP_CODEC = RecordCodecBuilder.mapCodec(
      instance -> instance
          .group(
              Codec.STRING.optionalFieldOf("thread_name", "").forGetter(RecipeExecutionOptions::threadName),
              Codec.INT.optionalFieldOf("max_threads", 0).forGetter(RecipeExecutionOptions::maxThreads))
          .apply(instance, RecipeExecutionOptions::new));
  public static final Codec<RecipeExecutionOptions> CODEC = MAP_CODEC.codec();

  public RecipeExecutionOptions {
    Objects.requireNonNull(threadName, "threadName");
    if (!threadName.isEmpty() && !threadName.matches("[a-zA-Z0-9_.-]{1,64}")
        || maxThreads < 0
        || maxThreads > MAX_THREADS) {
      throw new IllegalArgumentException("Invalid recipe factory thread selection");
    }
  }

  public boolean accepts(String threadId) {
    return threadName.isEmpty() || threadName.equals(threadId);
  }
}
