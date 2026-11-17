package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.recipe.ChemicalRequirement;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import org.jetbrains.annotations.ApiStatus;

/**
 * Pure frozen Chemical draws. IO and progress select independent seed domains;
 * row order and Random consumption are stable across retries and reloads.
 * Progress -1 denotes startup/completion; nonnegative values identify tick IO.
 */
@ApiStatus.Internal
public final class ChemicalRecipeSampling {
  private ChemicalRecipeSampling() {
  }

  public static List<ChemicalRequirement> sample(List<ChemicalRequirement> rows, long seed, int instances, RecipeModifier.Io io, int progress) {
    if (instances < 1 || progress < -1) {
      throw new IllegalArgumentException("Invalid chemical sampling scope");
    }
    Objects.requireNonNull(io, "io");
    long domain = io == RecipeModifier.Io.INPUT ? 0x16A8B348C96AC3BFL : 0x67E95C90FD643253L;
    var random = new Random(seed ^ domain ^ (progress < 0 ? 0 : 0x9E3779B97F4A7C15L * (progress + 1L)));
    var sampled = new ArrayList<ChemicalRequirement>();
    for (var row : rows) {
      if (row.amount() % instances != 0) {
        throw new IllegalArgumentException("Frozen chemical amount is not an exact per-instance demand");
      }
      int successes = row.chance() == 1 ? instances : 0;
      if (row.chance() > 0 && row.chance() < 1) {
        for (int attempt = 0; attempt < instances; attempt++) {
          if (random.nextDouble() < row.chance()) {
            successes++;
          }
        }
      }
      if (successes > 0) {
        sampled.add(
            new ChemicalRequirement(
                row.chemical(),
                Math.multiplyExact(row.amount() / instances, successes),
                row.selector(),
                1,
                row.ignoreOutputCheck()));
      }
    }

    return List.copyOf(sampled);
  }
}
