package com.lw.modularmachine.machine.recipe;

import java.util.Random;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Deterministic frozen output sampling; input/output random streams stay
 * independent.
 */
@org.jetbrains.annotations.ApiStatus.Internal
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
      long successes = chance >= 1 ? attempts : 0;
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
      var copy = row.copy();
      copy.putLong("amount", Math.multiplyExact(row.getLong("amount"), successes));
      copy.remove("chance");
      copy.remove("parallel_unaffected");
      result.add(copy);
    }
    return result;
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
