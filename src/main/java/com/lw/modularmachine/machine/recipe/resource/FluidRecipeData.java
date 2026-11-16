package com.lw.modularmachine.machine.recipe.resource;

import com.lw.modularmachine.api.recipe.FluidRequirement;
import com.lw.modularmachine.machine.recipe.MachineRecipeJson;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Objects;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Component-preserving fluid rows and per-tick input, independent of real
 * tanks.
 */
@ApiStatus.Internal
public record FluidRecipeData(List<FluidRequirement> inputs, List<FluidRequirement> outputs, FluidStack perTick) {
  public static final FluidRecipeData NONE = new FluidRecipeData(List.of(), List.of(), FluidStack.EMPTY);
  public static final MapCodec<FluidRecipeData> CODEC = RecordCodecBuilder.mapCodec(
      instance -> instance.group(
          FluidRequirement.CODEC.listOf().optionalFieldOf("fluid_inputs", List.of()).forGetter(FluidRecipeData::inputs),
          FluidRequirement.CODEC.listOf().optionalFieldOf("fluid_outputs", List.of())
              .forGetter(FluidRecipeData::outputs),
          FluidStack.CODEC.optionalFieldOf("fluid_per_tick", FluidStack.EMPTY).forGetter(FluidRecipeData::perTick))
          .apply(instance, FluidRecipeData::new));

  public FluidRecipeData {
    inputs = List.copyOf(inputs);
    outputs = List.copyOf(outputs);
    if (inputs.size() > MachineRecipeJson.MAX_DEMANDS || outputs.size() > MachineRecipeJson.MAX_DEMANDS) {
      throw new IllegalArgumentException("Too many fluid requirements");
    }
    perTick = Objects.requireNonNull(perTick, "perTick").copy();
  }

  @Override
  public FluidStack perTick() {
    return perTick.copy();
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof FluidRecipeData data
            && inputs.equals(data.inputs)
            && outputs.equals(data.outputs)
            && FluidStack.matches(perTick, data.perTick);
  }

  @Override
  public int hashCode() {
    return Objects.hash(inputs, outputs, perTick.getFluid(), perTick.getComponents(), perTick.getAmount());
  }
}
