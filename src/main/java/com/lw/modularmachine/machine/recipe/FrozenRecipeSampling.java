package com.lw.modularmachine.machine.recipe;

import java.util.Random;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.ApiStatus;

/**
 * Deterministic frozen output sampling; input/output random streams stay
 * independent.
 */
@ApiStatus.Internal
final class FrozenRecipeSampling {
  private FrozenRecipeSampling() {
  }

  static ListTag sampledOutputs(ListTag source, long seed, int instances) {
    var random = new Random(seed);
    var result = new ListTag();
    for (int i = 0; i < source.size(); i++) {
      var row = source.getCompound(i);
      double chance = row.contains("chance", Tag.TAG_DOUBLE) ? row.getDouble("chance") : 1.0D;
      int attempts = row.getBoolean("parallel_unaffected") ? 1 : instances;
      long total = 0;
      long max = row.getLong("amount");
      long min = row.getLong("max_amount") > 0 ? row.getLong("min_amount") : max;
      for (int attempt = 0; attempt < attempts; attempt++) {
        if (chance >= 1 || chance > 0 && random.nextDouble() < chance) {
          long quantity = min == max ? max : sampleRange(random, min, max);
          total = Math.addExact(total, quantity);
        }
      }
      if (total == 0) {
        continue;
      }
      var copy = row.copy();
      copy.putLong("amount", total);
      copy.remove("chance");
      copy.remove("parallel_unaffected");
      copy.remove("min_amount");
      copy.remove("max_amount");
      result.add(copy);
    }
    return result;
  }

  private static long sampleRange(Random random, long minimum, long maximum) {
    long width = maximum - minimum + 1;
    if (width <= 0) {
      return random.nextLong() & Long.MAX_VALUE;
    }
    return minimum + random.nextLong(width);
  }

  static ListTag sampledFluids(ListTag source, long seed, int instances, boolean input) {
    var random = new Random(seed ^ (input ? 0x52AA06AB184ECB22L : 0x73BFE15FD01F13B7L));
    var result = new ListTag();
    for (int index = 0; index < source.size(); index++) {
      var row = source.getCompound(index);
      double chance = row.contains("chance", Tag.TAG_ANY_NUMERIC) ? row.getDouble("chance") : 1;
      int attempts = row.getBoolean("parallel_unaffected") ? 1 : instances;
      int successes = chance >= 1 ? attempts : 0;
      if (chance > 0 && chance < 1) {
        for (int attempt = 0; attempt < attempts; attempt++) {
          if (random.nextDouble() < chance) {
            successes++;
          }
        }
      }
      if (successes == 0) {
        continue;
      }
      int fullAmount = row.getInt("amount");
      if (fullAmount % attempts != 0) {
        throw new IllegalArgumentException("Frozen fluid quantity is not an exact per-instance demand");
      }
      var copy = row.copy();
      copy.putInt("amount", Math.multiplyExact(fullAmount / attempts, successes));
      copy.remove("chance");
      copy.remove("parallel_unaffected");
      result.add(copy);
    }
    return result;
  }
}
