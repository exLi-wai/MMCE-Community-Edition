package com.lw.modularmachine.machine.recipe.search;

import org.jetbrains.annotations.ApiStatus;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable resource view used by the pure recipe planner. */
@ApiStatus.Internal
public record ResourceSnapshot<K>(long revision, Map<K, Long> inputs, List<OutputSlot<K>> outputs) {
  public ResourceSnapshot {
    if (revision < 0) {
      throw new IllegalArgumentException("revision must be non-negative");
    }
    inputs = Map.copyOf(Objects.requireNonNull(inputs, "inputs"));
    outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
    for (var entry : inputs.entrySet()) {
      if (entry.getKey() instanceof String text && text.isBlank() || entry.getValue() < 0) {
        throw new IllegalArgumentException("Invalid input resource");
      }
    }
  }

  public long inputCount(K key) {
    return inputs.getOrDefault(Objects.requireNonNull(key, "key"), 0L);
  }

  /**
   * Nonempty limits restrict accepted keys; an empty map preserves the
   * unrestricted test model.
   */
  public interface SlotLimit<K> {
    long capacityFor(K key, long capacity);
  }

  private record KeyLimits<K>(Map<K, Long> values) implements SlotLimit<K> {
    @Override
    public long capacityFor(K key, long capacity) {
      return values.isEmpty() ? capacity : values.getOrDefault(key, 0L);
    }
  }

  public record OutputSlot<K>(K key, long count, long capacity, SlotLimit<K> limits) {
    public OutputSlot(K key, long count, long capacity) {
      this(key, count, capacity, Map.of());
    }

    public OutputSlot(K key, long count, long capacity, Map<K, Long> limits) {
      this(key, count, capacity, new KeyLimits<>(Map.copyOf(limits)));
      for (long limit : limits.values()) {
        if (limit < 0 || limit > capacity) {
          throw new IllegalArgumentException("Invalid key-specific capacity");
        }
      }
    }

    public OutputSlot {
      if (key instanceof String text && text.isBlank()) {
        throw new IllegalArgumentException("Blank output key");
      }
      if (count < 0 || capacity < 0 || count > capacity) {
        throw new IllegalArgumentException("Invalid output slot");
      }
      if (key == null && count != 0) {
        throw new IllegalArgumentException("Empty slot cannot contain items");
      }
      Objects.requireNonNull(limits, "limits");
    }

    public long capacityFor(K requested) {
      Objects.requireNonNull(requested, "requested");
      return limits.capacityFor(requested, capacity);
    }

    public boolean canAccept(K requested) {
      return count < capacityFor(requested) && (key == null || key.equals(requested));
    }
  }
}
