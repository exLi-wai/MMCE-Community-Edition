package com.lw.modularmachine.api.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashMap;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

/**
 * Native chemical requirements; these declarations contain no live registry or
 * storage objects.
 */
public record ChemicalRecipeData(
    List<ChemicalRequirement> inputs,
    List<ChemicalRequirement> outputs,
    List<ChemicalRequirement> perTick) {
  public static final int MAX_REQUIREMENTS = 256;
  public static final ChemicalRecipeData NONE = new ChemicalRecipeData(List.of(), List.of(), List.of());
  public static final Codec<ChemicalRecipeData> CODEC = RecordCodecBuilder.create(
      instance -> instance.group(
          ChemicalRequirement.CODEC.listOf().optionalFieldOf("inputs", List.of()).forGetter(ChemicalRecipeData::inputs),
          ChemicalRequirement.CODEC.listOf().optionalFieldOf("outputs", List.of())
              .forGetter(ChemicalRecipeData::outputs),
          ChemicalRequirement.CODEC.listOf().optionalFieldOf("per_tick", List.of())
              .forGetter(ChemicalRecipeData::perTick))
          .apply(instance, ChemicalRecipeData::new));

  public ChemicalRecipeData {
    inputs = checked(inputs);
    outputs = checked(outputs);
    perTick = checked(perTick);
  }

  public boolean isEmpty() {
    return inputs.isEmpty() && outputs.isEmpty() && perTick.isEmpty();
  }

  private static List<ChemicalRequirement> checked(List<ChemicalRequirement> values) {
    var result = List.copyOf(values);
    if (result.size() > MAX_REQUIREMENTS) {
      throw new IllegalArgumentException("Too many chemical requirements");
    }
    var totals = new HashMap<Key, Long>();
    for (var requirement : result) {
      totals.merge(new Key(requirement.chemical(), requirement.selector()), requirement.amount(), Math::addExact);
    }

    return result;
  }

  private record Key(ResourceLocation chemical, String selector) {
  }
}
