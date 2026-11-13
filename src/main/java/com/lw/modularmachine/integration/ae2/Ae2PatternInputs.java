package com.lw.modularmachine.integration.ae2;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.pattern.AEProcessingPattern;
import com.lw.modularmachine.machine.network.NetworkPortResource;
import com.lw.modularmachine.machine.network.pattern.PatternDefinition;
import com.lw.modularmachine.machine.network.pattern.PatternResourceKey;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal AE 19.2.18 processing decoder and detached input preparation. No
 * network extraction, buffer write, receipt or CPU authorization happens here.
 */
@ApiStatus.Internal
public final class Ae2PatternInputs {
  public record Decoded(
      PatternDefinition definition,
      Map<PatternResourceKey, Long> inputs,
      Map<PatternResourceKey, Long> outputs) {
    public Decoded {
      Objects.requireNonNull(definition);
      inputs = Map.copyOf(inputs);
      outputs = Map.copyOf(outputs);
      if (inputs.isEmpty()
          || outputs.isEmpty()
          || inputs.values().stream().anyMatch(amount -> amount <= 0)
          || outputs.values().stream().anyMatch(amount -> amount <= 0)) {
        throw new IllegalArgumentException("Empty or invalid processing pattern resources");
      }
    }
  }

  private final Function<AEKey, NetworkPortResource> chemicalDecoder;

  /**
   * A null decoder rejects every non-item/fluid key, including missing AppMek.
   */
  public Ae2PatternInputs(Function<AEKey, NetworkPortResource> chemicalDecoder) {
    this.chemicalDecoder = chemicalDecoder;
  }

  public Optional<Decoded> decode(IPatternDetails pattern, ServerLevel level) {
    ServerThreadGuard.require(level, "Pattern decode");
    if (!supported(pattern)) {
      return Optional.empty();
    }
    try {
      var processing = (AEProcessingPattern) pattern;
      var definition = PatternDefinition.capture(pattern.getDefinition().toStack(1), level.registryAccess());

      return Optional.of(
          new Decoded(
              definition,
              decodeStacks(processing.getSparseInputs(), level),
              decodeStacks(processing.getSparseOutputs(), level)));
    } catch (RuntimeException invalid) {
      return Optional.empty();
    }
  }

  /**
   * All expected failures occur before the caller acquires/writes real cache. The
   * original AE holders are never passed to the pattern's input sink.
   */
  public Optional<Map<PatternResourceKey, Long>> prepareDelivery(Decoded expected, IPatternDetails pattern, KeyCounter[] holders, ServerLevel level) {
    ServerThreadGuard.require(level, "Pattern input preparation");
    if (!supported(pattern) || expected == null || holders == null) {
      return Optional.empty();
    }
    try {
      if (!expected.definition()
          .equals(PatternDefinition.capture(pattern.getDefinition().toStack(1), level.registryAccess()))
          || holders.length != pattern.getInputs().length) {
        return Optional.empty();
      }
      var originals = new LinkedHashMap<PatternResourceKey, Long>();
      var copies = new KeyCounter[holders.length];
      for (int index = 0; index < holders.length; index++) {
        if (holders[index] == null) {
          return Optional.empty();
        }
        copies[index] = new KeyCounter();
        for (var entry : holders[index]) {
          long amount = entry.getLongValue();
          // AE counters may retain zero entries; they are not delivered stock.
          if (amount == 0) {
            continue;
          }
          var key = decodeKey(entry.getKey(), level);
          add(originals, key, amount);
          copies[index].add(entry.getKey(), amount);
        }
      }
      if (!originals.equals(expected.inputs())) {
        return Optional.empty();
      }
      var sink = new LinkedHashMap<PatternResourceKey, Long>();
      pattern.pushInputsToExternalInventory(copies, (key, amount) -> add(sink, decodeKey(key, level), amount));
      if (!sink.equals(originals)) {
        return Optional.empty();
      }

      return Optional.of(Collections.unmodifiableMap(sink));
    } catch (RuntimeException invalid) {
      // Nothing was committed, so AE may reinject its own unchanged holders.
      return Optional.empty();
    }
  }

  private static boolean supported(IPatternDetails pattern) {
    // A generic push-support flag also accepts crafting/smithing patterns.
    // Subclasses need their own verified contract before being admitted.
    return pattern != null && pattern.getClass() == AEProcessingPattern.class;
  }

  private Map<PatternResourceKey, Long> decodeStacks(List<GenericStack> stacks, ServerLevel level) {
    var result = new LinkedHashMap<PatternResourceKey, Long>();
    for (var stack : stacks) {
      if (stack != null) {
        add(result, decodeKey(stack.what(), level), stack.amount());
      }
    }

    return result;
  }

  private PatternResourceKey decodeKey(AEKey key, ServerLevel level) {
    NetworkPortResource resource;
    if (key instanceof AEItemKey item) {
      resource = new NetworkPortResource.Item(item.toStack(1));
    } else if (key instanceof AEFluidKey fluid) {
      resource = new NetworkPortResource.Fluid(fluid.toStack(1));
    } else {
      resource = chemicalDecoder == null ? null : chemicalDecoder.apply(key);
      if (!(resource instanceof NetworkPortResource.Chemical)) {
        throw new IllegalArgumentException("Unsupported pattern resource key");
      }
    }

    return PatternResourceKey.capture(resource, level.registryAccess());
  }

  private static void add(Map<PatternResourceKey, Long> target, PatternResourceKey key, long amount) {
    if (amount <= 0) {
      throw new IllegalArgumentException("Invalid processing pattern quantity");
    }
    target.merge(key, amount, Math::addExact);
  }
}
