package com.lw.modularmachine.machine.recipe.resource;

import com.lw.modularmachine.api.recipe.FluidRequirement;
import com.lw.modularmachine.machine.recipe.MachineRecipeJson;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.ApiStatus;

/** Component-preserving fluid rows, with independently routed tick phases. */
@ApiStatus.Internal
public record FluidRecipeData(
    List<FluidRequirement> inputs,
    List<FluidRequirement> outputs,
    List<FluidRequirement> perTickInputs,
    List<FluidRequirement> perTickOutputs) {
  public static final FluidRecipeData NONE = new FluidRecipeData(List.of(), List.of(), List.of(), List.of());
  public static final MapCodec<FluidRecipeData> CODEC = RecordCodecBuilder.mapCodec(
      instance -> instance.group(
          FluidRequirement.CODEC.listOf().optionalFieldOf("fluid_inputs", List.of()).forGetter(FluidRecipeData::inputs),
          FluidRequirement.CODEC.listOf().optionalFieldOf("fluid_outputs", List.of())
              .forGetter(FluidRecipeData::outputs),
          FluidRequirement.CODEC.listOf().optionalFieldOf("fluid_per_tick_inputs", List.of())
              .forGetter(FluidRecipeData::perTickInputs),
          FluidRequirement.CODEC.listOf().optionalFieldOf("fluid_per_tick_outputs", List.of())
              .forGetter(FluidRecipeData::perTickOutputs),
          FluidStack.CODEC.optionalFieldOf("fluid_per_tick", FluidStack.EMPTY).forGetter(value -> FluidStack.EMPTY))
          .apply(instance, FluidRecipeData::decoded));

  public FluidRecipeData(List<FluidRequirement> inputs, List<FluidRequirement> outputs, FluidStack perTick) {
    this(inputs, outputs, perTick.isEmpty() ? List.of() : List.of(new FluidRequirement(perTick, 1)), List.of());
  }

  public FluidRecipeData {
    inputs = checked(inputs);
    outputs = checked(outputs);
    perTickInputs = checked(perTickInputs);
    perTickOutputs = checked(perTickOutputs);
  }

  /** Existing single-row callers retain a detached first-row preview. */
  public FluidStack perTick() {
    return perTickInputs.isEmpty() ? FluidStack.EMPTY : perTickInputs.getFirst().stack();
  }

  private static FluidRecipeData decoded(List<FluidRequirement> inputs, List<FluidRequirement> outputs, List<FluidRequirement> perTickInputs, List<FluidRequirement> perTickOutputs, FluidStack legacy) {
    if (!legacy.isEmpty()) {
      if (!perTickInputs.isEmpty()) {
        throw new IllegalArgumentException("Use either fluid_per_tick or fluid_per_tick_inputs");
      }
      perTickInputs = List.of(new FluidRequirement(legacy, 1));
    }

    return new FluidRecipeData(inputs, outputs, perTickInputs, perTickOutputs);
  }

  private static List<FluidRequirement> checked(List<FluidRequirement> rows) {
    var result = List.copyOf(rows);
    if (result.size() > MachineRecipeJson.MAX_DEMANDS) {
      throw new IllegalArgumentException("Too many fluid requirements");
    }
    // Native validation rejects fixed per-tick declarations. The internal tick
    // image may also contain due one-shot rows whose fixed parallel rule remains.

    return result;
  }
}
