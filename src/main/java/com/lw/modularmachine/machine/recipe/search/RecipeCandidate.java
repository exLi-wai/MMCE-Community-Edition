package com.lw.modularmachine.machine.recipe.search;

import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.api.smart.SmartNumberRequirement;
import java.util.List;
import java.util.Objects;

/** A compiled candidate with no mutable machine or world references. */
@ApiStatus.Internal
public record RecipeCandidate<K>(
    String id,
    int priority,
    List<Input<K>> inputs,
    List<Output<K>> outputs,
    List<SmartNumberRequirement> interfaceInputs) {
  public RecipeCandidate(String id, int priority, List<Input<K>> inputs, List<Output<K>> outputs) {
    this(id, priority, inputs, outputs, List.of());
  }
  public RecipeCandidate {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("Recipe id is required");
    }
    inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
    outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
    interfaceInputs = List.copyOf(interfaceInputs);
  }

  /**
   * Internal capacity placement for an already-paid run. Native recipe JSON still
   * requires positive inputs.
   */
  public static <K> RecipeCandidate<K> outputOnly(String id, List<Output<K>> outputs) {
    if (outputs.isEmpty()) {
      throw new IllegalArgumentException("Output placement needs at least one claim");
    }
    return new RecipeCandidate<>(id, 0, List.of(), outputs);
  }

  public record Input<K>(K key, long count, double chance) {
    public Input(K key, long count) {
      this(key, count, 1.0D);
    }

    public Input {
      Objects.requireNonNull(key, "key");
      if (key instanceof String text && text.isBlank()
          || count <= 0
          || !Double.isFinite(chance)
          || chance < 0
          || chance > 1) {
        throw new IllegalArgumentException("Invalid recipe input");
      }
    }
  }

  public record Output<K>(K key, long count) {
    public Output {
      Objects.requireNonNull(key, "key");
      if (key instanceof String text && text.isBlank() || count <= 0) {
        throw new IllegalArgumentException("Invalid recipe output");
      }
    }
  }
}
