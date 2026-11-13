package com.lw.modularmachine.machine.network.pattern;

import com.lw.modularmachine.api.upgrade.FactoryUpgradeSnapshot;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.ApiStatus;

/** Internal detached processing declaration; never contains live AE objects. */
@ApiStatus.Internal
public record PatternRecipeDefinition(
    PatternDefinition definition,
    Map<PatternResourceKey, Long> inputs,
    Map<PatternResourceKey, Long> outputs) {
  /** The same duration and fluid fee that will be frozen into one native run. */
  public record ExecutionCosts(int duration, int fluidPerTick) {
    public ExecutionCosts {
      if (duration <= 0 || fluidPerTick < 0) {
        throw new IllegalArgumentException("Invalid pattern execution costs");
      }
    }

    public long totalPerTick(long amount) {
      if (amount < 0) {
        throw new IllegalArgumentException("Negative pattern tick cost");
      }

      return Math.multiplyExact(amount, duration);
    }
  }

  public PatternRecipeDefinition {
    Objects.requireNonNull(definition);
    inputs = Map.copyOf(inputs);
    outputs = Map.copyOf(outputs);
    if (inputs.isEmpty()
        || outputs.isEmpty()
        || inputs.values().stream().anyMatch(value -> value <= 0)
        || outputs.values().stream().anyMatch(value -> value <= 0)) {
      throw new IllegalArgumentException("Invalid processing declaration");
    }
  }

  /**
   * Match any advertised output to one native execution, including all of its
   * tick fees. Other native input keys may be supplied by ordinary storage.
   */
  public boolean matchesNativeDeclaration(Map<PatternResourceKey, Long> nativeInputs, Map<PatternResourceKey, Long> nativeOutputs) {
    return matchesDeclaredAmounts(inputs, outputs, nativeInputs, nativeOutputs);
  }

  public static ExecutionCosts executionCosts(int baseDuration, int baseFluidPerTick, FactoryUpgradeSnapshot upgrades) {
    Objects.requireNonNull(upgrades);

    return new ExecutionCosts(
        upgrades.durationFor(baseDuration),
        Math.toIntExact(upgrades.fluidPerTick(baseFluidPerTick)));
  }

  static <K> boolean matchesDeclaredAmounts(Map<K, Long> patternInputs, Map<K, Long> patternOutputs, Map<K, Long> nativeInputs, Map<K, Long> nativeOutputs) {
    boolean outputMatches = patternOutputs.entrySet().stream()
        .anyMatch(entry -> Objects.equals(nativeOutputs.get(entry.getKey()), entry.getValue()));
    // A shared receipt may contain multiple deliveries. A cheaper declaration
    // must never authorize spending another delivery to complete one execution.
    return outputMatches
        && patternInputs.entrySet().stream()
            .allMatch(entry -> Objects.equals(nativeInputs.get(entry.getKey()), entry.getValue()));
  }
}
