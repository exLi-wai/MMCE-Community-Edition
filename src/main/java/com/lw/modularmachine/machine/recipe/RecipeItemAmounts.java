package com.lw.modularmachine.machine.recipe;

import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.ApiStatus;

/** Internal exact inclusive quantity sampling, including the long boundary. */
@ApiStatus.Internal
public final class RecipeItemAmounts {
  private RecipeItemAmounts() {
  }

  public static long sample(long minimum, long maximum, RandomSource random) {
    if (minimum < 0 || maximum < minimum) {
      throw new IllegalArgumentException("Invalid item quantity range");
    }
    if (minimum == maximum) {
      return minimum;
    }
    long width = maximum - minimum + 1;
    if (width <= 0) {
      // Only the full nonnegative long range has an overflowing inclusive width.
      return random.nextLong() & Long.MAX_VALUE;
    }
    long bits;
    long value;
    do {
      bits = random.nextLong() >>> 1;
      value = bits % width;
    } while (bits - value + width - 1 < 0);

    return minimum + value;
  }
}
